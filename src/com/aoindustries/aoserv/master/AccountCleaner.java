package com.aoindustries.aoserv.master;

/*
 * Copyright 2003-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.*;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.email.*;
import com.aoindustries.profiler.*;
import com.aoindustries.util.*;
import java.io.*;
import java.sql.*;
import java.util.*;

/**
 * Automatically cleans out old account resources.  The resources are left in tact for one month before cleaning.
 *
 * @author  AO Industries, Inc.
 */
final public class AccountCleaner implements CronJob {

    /**
     * The maximum time for a cleaning.
     */
    private static final long TIMER_MAX_TIME=20L*60*1000;

    /**
     * The interval in which the administrators will be reminded.
     */
    private static final long TIMER_REMINDER_INTERVAL=6L*60*60*1000;

    private static boolean started=false;

    public static void start() {
        Profiler.startProfile(Profiler.UNKNOWN, AccountCleaner.class, "start()", null);
        try {
            synchronized(System.out) {
                if(!started) {
                    System.out.print("Starting AccountCleaner: ");
                    CronDaemon.addCronJob(new AccountCleaner(), MasterServer.getErrorHandler());
                    started=true;
                    System.out.println("Done");
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    private AccountCleaner() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, AccountCleaner.class, "<init>()", null);
        Profiler.endProfile(Profiler.INSTANTANEOUS);
    }
    
    /**
     * Runs at 5:25 am daily.
     */
    public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        return
            minute==25
            && hour==5
        ;
    }

    public int getCronJobScheduleMode() {
        return CRON_JOB_SCHEDULE_SKIP;
    }

    public String getCronJobName() {
        return "AccountCleaner";
    }

    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY-1;
    }

    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek) {
        Profiler.startProfile(Profiler.UNKNOWN, AccountCleaner.class, "runCronJob(int,int,int,int,int)", null);
        try {
            try {
                ProcessTimer timer=new ProcessTimer(
                    MasterServer.getRandom(),
                    MasterConfiguration.getWarningSmtpServer(),
                    MasterConfiguration.getWarningEmailFrom(),
                    MasterConfiguration.getWarningEmailTo(),
                    "Account Cleaner",
                    "Cleaning old account resources",
                    TIMER_MAX_TIME,
                    TIMER_REMINDER_INTERVAL
                );
                try {
                    timer.start();

                    // Start the transaction
                    InvalidateList invalidateList=new InvalidateList();
                    MasterDatabaseConnection conn=(MasterDatabaseConnection)MasterDatabase.getDatabase().createDatabaseConnection();
                    try {
                        BackupDatabaseConnection backupConn=(BackupDatabaseConnection)BackupDatabase.getDatabase().createDatabaseConnection();
                        try {
                            boolean connRolledBack=false;
                            boolean backupConnRolledBack=false;
                            try {
                                StringBuilder message=new StringBuilder();

                                Timestamp now=new Timestamp(System.currentTimeMillis());

                                // backup_reports
                                {
                                    // Those that are part of canceled accounts
                                    if(
                                        backupConn.executeBooleanQuery(
                                            Connection.TRANSACTION_READ_COMMITTED,
                                            true,
                                            true,
                                            "select\n"
                                            + "  (\n"
                                            + "    select\n"
                                            + "      br.pkey\n"
                                            + "    from\n"
                                            + "      backup_reports br,\n"
                                            + "      packages pk,\n"
                                            + "      businesses bu\n"
                                            + "    where\n"
                                            + "      br.package=pk.pkey\n"
                                            + "      and pk.accounting=bu.accounting\n"
                                            + "      and bu.canceled is not null\n"
                                            + "      and (?::date-bu.canceled::date)>30\n"
                                            + "    limit 1\n"
                                            + "  ) is not null",
                                            now
                                        )
                                    ) {
                                        backupConn.executeUpdate(
                                            "delete from\n"
                                            + "  backup_reports\n"
                                            + "where\n"
                                            + "  pkey in (\n"
                                            + "    select\n"
                                            + "      br.pkey\n"
                                            + "    from\n"
                                            + "      backup_reports br,\n"
                                            + "      packages pk,\n"
                                            + "      businesses bu\n"
                                            + "    where\n"
                                            + "      br.package=pk.pkey\n"
                                            + "      and pk.accounting=bu.accounting\n"
                                            + "      and bu.canceled is not null\n"
                                            + "      and (?::date-bu.canceled::date)>30\n"
                                            + "  )",
                                            now
                                        );
                                        invalidateList.addTable(conn, SchemaTable.TableID.BACKUP_REPORTS, InvalidateList.allBusinesses, InvalidateList.allServers, false);
                                    }

                                    // Those that are older than BackupReport.SendmailSmtpStat.MAX_REPORT_AGE
                                    if(
                                        backupConn.executeBooleanQuery(
                                            Connection.TRANSACTION_READ_COMMITTED,
                                            true,
                                            true,
                                            "select\n"
                                            + "  (\n"
                                            + "    select\n"
                                            + "      pkey\n"
                                            + "    from\n"
                                            + "      backup_reports\n"
                                            + "    where\n"
                                            + "      (?::date-date)>"+BackupReport.MAX_REPORT_AGE+"\n"
                                            + "    limit 1\n"
                                            + "  ) is not null",
                                            now
                                        )
                                    ) {
                                        backupConn.executeUpdate(
                                            "delete from\n"
                                            + "  backup_reports\n"
                                            + "where\n"
                                            + "  (?::date-date)>"+BackupReport.MAX_REPORT_AGE,
                                            now
                                        );
                                        invalidateList.addTable(conn, SchemaTable.TableID.BACKUP_REPORTS, InvalidateList.allBusinesses, InvalidateList.allServers, false);
                                    }
                                }

                                // businesses
                                {
                                    {
                                        // look for any accounts that have been canceled but not disabled
                                        List<String> bus=conn.executeStringListQuery(
                                            Connection.TRANSACTION_READ_COMMITTED,
                                            true,
                                            "select accounting from businesses where parent=? and canceled is not null and disable_log is null",
                                            BusinessHandler.getRootBusiness()
                                        );
                                        if(bus.size()>0) {
                                            message
                                                .append("The following ")
                                                .append(bus.size()==1?"business has":"businesses have")
                                                .append(" been canceled but not disabled:\n");
                                            for(int c=0;c<bus.size();c++) message.append(bus.get(c)).append('\n');
                                            message.append('\n');
                                        }
                                    }

                                    {
                                        // look for any accounts that have been disabled for over two months but not canceled
                                        List<String> bus=conn.executeStringListQuery(
                                            Connection.TRANSACTION_READ_COMMITTED,
                                            true,
                                            "select\n"
                                            + "  bu.accounting\n"
                                            + "from\n"
                                            + "  businesses bu,\n"
                                            + "  disable_log dl\n"
                                            + "where\n"
                                            + "  bu.canceled is null\n"
                                            + "  and bu.disable_log=dl.pkey\n"
                                            + "  and (?::date-dl.time::date)>60",
                                            now
                                        );
                                        if(bus.size()>0) {
                                            message
                                                .append("The following ")
                                                .append(bus.size()==1?"business has":"businesses have")
                                                .append(" been disabled for over 60 days but not canceled:\n");
                                            for(int c=0;c<bus.size();c++) message.append(bus.get(c)).append('\n');
                                            message.append('\n');
                                        }
                                    }
                                }

                                // credit_cards
                                {
                                    IntList ccs=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  cc.pkey\n"
                                        + "from\n"
                                        + "  credit_cards cc,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  cc.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<ccs.size();c++) {
                                        CreditCardHandler.removeCreditCard(conn, invalidateList, ccs.getInt(c));
                                    }
                                }

                                // business_administrators over 30 days
                                // remove if balance is zero and has not been used in actions or transactions
                                {
                                    List<String> bas=conn.executeStringListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  ba.username\n"
                                        + "from\n"
                                        + "  business_administrators ba,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  (select ac.pkey from actions ac where ac.administrator=ba.username limit 1) is null\n"
                                        + "  and (select dl.pkey from disable_log dl where dl.disabled_by=ba.username limit 1) is null\n"
                                        + "  and (select pk2.name from packages pk2 where pk2.created_by=ba.username limit 1) is null\n"
                                        + "  and (select ti.pkey from tickets ti where ti.created_by=ba.username or ti.assigned_to=ba.username or ti.closed_by=ba.username limit 1) is null\n"
                                        + "  and (select tr.transid from transactions tr where tr.username=ba.username limit 1) is null\n"
                                        + "  and ba.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<bas.size();c++) {
                                        String username=bas.get(c);
                                        String business=UsernameHandler.getBusinessForUsername(conn, username);
                                        int balance=TransactionHandler.getConfirmedAccountBalance(conn, business);
                                        if(balance<=0) {
                                            BusinessHandler.removeBusinessAdministrator(conn, invalidateList, username);
                                        }
                                    }
                                }

                                // cvs_repositories
                                {
                                    IntList crs=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  cr.pkey\n"
                                        + "from\n"
                                        + "  cvs_repositories cr,\n"
                                        + "  linux_server_accounts lsa,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  cr.linux_server_account=lsa.pkey\n"
                                        + "  and lsa.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<crs.size();c++) {
                                        CvsHandler.removeCvsRepository(conn, invalidateList, crs.getInt(c));
                                    }
                                }

                                // dns_zones
                                {
                                    List<String> dzs=conn.executeStringListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  dz.zone\n"
                                        + "from\n"
                                        + "  dns_zones dz,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  dz.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<dzs.size();c++) {
                                        DNSHandler.removeDNSZone(conn, invalidateList, (String)dzs.get(c));
                                    }
                                }

                                // email_lists
                                {
                                    IntList els=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  el.pkey\n"
                                        + "from\n"
                                        + "  email_lists el,\n"
                                        + "  linux_server_groups lsg,\n"
                                        + "  linux_groups lg,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  el.linux_group=lsg.pkey\n"
                                        + "  and lsg.name=lg.name\n"
                                        + "  and lg.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<els.size();c++) {
                                        EmailHandler.removeEmailList(conn, invalidateList, els.getInt(c));
                                    }
                                }

                                // email_domains
                                {
                                    IntList eds=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  ed.pkey\n"
                                        + "from\n"
                                        + "  email_domains ed,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  ed.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<eds.size();c++) {
                                        EmailHandler.removeEmailDomain(conn, invalidateList, eds.getInt(c));
                                    }
                                }

                                // email_pipes
                                {
                                    IntList eps=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  ep.pkey\n"
                                        + "from\n"
                                        + "  email_pipes ep,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  ep.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<eps.size();c++) {
                                        EmailHandler.removeEmailPipe(conn, invalidateList, eps.getInt(c));
                                    }
                                }

                                // email_smtp_relays
                                {
                                    IntList esrs=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  esr.pkey\n"
                                        + "from\n"
                                        + "  email_smtp_relays esr,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  esr.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<esrs.size();c++) {
                                        EmailHandler.removeEmailSmtpRelay(conn, invalidateList, esrs.getInt(c));
                                    }
                                }

                                // file_backup_settings
                                {
                                    IntList fbss=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  fbs.pkey\n"
                                        + "from\n"
                                        + "  businesses bu,\n"
                                        + "  packages pk,\n"
                                        + "  file_backup_settings fbs\n"
                                        + "where\n"
                                        + "  bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30\n"
                                        + "  and bu.accounting=pk.accounting\n"
                                        + "  and pk.pkey=fbs.package",
                                        now
                                    );
                                    for(int c=0;c<fbss.size();c++) {
                                        BackupHandler.removeFileBackupSetting(conn, invalidateList, fbss.getInt(c));
                                    }
                                }

                                // httpd_sites
                                {
                                    IntList hss=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  hs.pkey\n"
                                        + "from\n"
                                        + "  httpd_sites hs,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  hs.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<hss.size();c++) {
                                        HttpdHandler.removeHttpdSite(conn, invalidateList, hss.getInt(c));
                                    }
                                }

                                // httpd_shared_tomcats
                                {
                                    IntList hsts=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  hst.pkey\n"
                                        + "from\n"
                                        + "  httpd_shared_tomcats hst,\n"
                                        + "  linux_server_groups lsg,\n"
                                        + "  linux_groups lg,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  hst.linux_server_group=lsg.pkey\n"
                                        + "  and lsg.name=lg.name\n"
                                        + "  and lg.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<hsts.size();c++) {
                                        HttpdHandler.removeHttpdSharedTomcat(conn, invalidateList, hsts.getInt(c));
                                    }
                                }

                                // interbase_backups
                                {
                                    IntList ibs=backupConn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  ib.pkey\n"
                                        + "from\n"
                                        + "  interbase_backups ib,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  ib.package=pk.pkey\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<ibs.size();c++) {
                                        InterBaseHandler.removeInterBaseBackup(conn, backupConn, invalidateList, ibs.getInt(c));
                                    }
                                }

                                // interbase_users
                                {
                                    List<String> ius=conn.executeStringListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  iu.username\n"
                                        + "from\n"
                                        + "  interbase_users iu,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  iu.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<ius.size();c++) {
                                        InterBaseHandler.removeInterBaseUser(conn, invalidateList, ius.get(c));
                                    }
                                }

                                // interbase_databases
                                {
                                    IntList ids=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  id.pkey\n"
                                        + "from\n"
                                        + "  interbase_databases id,\n"
                                        + "  interbase_db_groups idg,\n"
                                        + "  linux_server_groups lsg,\n"
                                        + "  linux_groups lg,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  id.db_group=idg.pkey\n"
                                        + "  and idg.linux_server_group=lsg.pkey\n"
                                        + "  and lsg.name=lg.name\n"
                                        + "  and lg.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<ids.size();c++) {
                                        InterBaseHandler.removeInterBaseDatabase(conn, invalidateList, ids.getInt(c));
                                    }
                                }

                                // interbase_db_groups
                                {
                                    IntList idgs=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  idg.pkey\n"
                                        + "from\n"
                                        + "  interbase_db_groups idg,\n"
                                        + "  linux_server_groups lsg,\n"
                                        + "  linux_groups lg,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  idg.linux_server_group=lsg.pkey\n"
                                        + "  and lsg.name=lg.name\n"
                                        + "  and lg.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<idgs.size();c++) {
                                        InterBaseHandler.removeInterBaseDBGroup(conn, invalidateList, idgs.getInt(c));
                                    }
                                }

                                // private_ftp_servers
                                {
                                    IntList pfss=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  pfs.net_bind\n"
                                        + "from\n"
                                        + "  private_ftp_servers pfs,\n"
                                        + "  net_binds nb,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  pfs.net_bind=nb.pkey\n"
                                        + "  and nb.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<pfss.size();c++) {
                                        FTPHandler.removePrivateFTPServer(conn, invalidateList, pfss.getInt(c));
                                    }
                                }

                                // net_binds
                                {
                                    IntList nbs=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  nb.pkey\n"
                                        + "from\n"
                                        + "  net_binds nb,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  nb.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<nbs.size();c++) {
                                        NetBindHandler.removeNetBind(conn, invalidateList, nbs.getInt(c));
                                    }

                                }

                                // ip_addresses
                                {
                                    IntList ias=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  ia.pkey\n"
                                        + "from\n"
                                        + "  ip_addresses ia,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  ia.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<ias.size();c++) {
                                        int ia=ias.getInt(c);
                                        IPAddressHandler.setIPAddressPackage(conn, invalidateList, ia, BusinessHandler.getRootBusiness());
                                        IPAddressHandler.releaseIPAddress(conn, invalidateList, ia);
                                    }
                                }

                                // linux_accounts
                                {
                                    List<String> las=conn.executeStringListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  la.username\n"
                                        + "from\n"
                                        + "  linux_accounts la,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  la.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<las.size();c++) {
                                        try {
                                            LinuxAccountHandler.removeLinuxAccount(conn, invalidateList, las.get(c));
                                        } catch(SQLException err) {
                                            System.err.println("SQLException trying to remove LinuxAccount: "+las.get(c));
                                            throw err;
                                        }
                                    }
                                }

                                // linux_groups
                                {
                                    List<String> lgs=conn.executeStringListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  lg.name\n"
                                        + "from\n"
                                        + "  linux_groups lg,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  lg.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<lgs.size();c++) {
                                        try {
                                            LinuxAccountHandler.removeLinuxGroup(conn, invalidateList, lgs.get(c));
                                        } catch(SQLException err) {
                                            System.err.println("SQLException trying to remove LinuxGroup: "+lgs.get(c));
                                            throw err;
                                        }
                                    }
                                }

                                // mysql_backups
                                {
                                    IntList mbs=backupConn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  mb.pkey\n"
                                        + "from\n"
                                        + "  mysql_backups mb,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  mb.package=pk.pkey\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<mbs.size();c++) {
                                        MySQLHandler.removeMySQLBackup(conn, backupConn, invalidateList, mbs.getInt(c));
                                    }
                                }

                                // mysql_databases
                                {
                                    IntList mds=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  md.pkey\n"
                                        + "from\n"
                                        + "  mysql_databases md,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  md.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<mds.size();c++) {
                                        MySQLHandler.removeMySQLDatabase(conn, invalidateList, mds.getInt(c));
                                    }
                                }

                                // mysql_users
                                {
                                    List<String> mus=conn.executeStringListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  mu.username\n"
                                        + "from\n"
                                        + "  mysql_users mu,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  mu.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<mus.size();c++) {
                                        MySQLHandler.removeMySQLUser(conn, invalidateList, mus.get(c));
                                    }
                                }

                                // postgres_backups
                                {
                                    IntList pbs=backupConn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  pb.pkey\n"
                                        + "from\n"
                                        + "  postgres_backups pb,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  pb.package=pk.pkey\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<pbs.size();c++) {
                                        PostgresHandler.removePostgresBackup(conn, backupConn, invalidateList, pbs.getInt(c));
                                    }
                                }

                                // postgres_databases
                                {
                                    IntList pds=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  pd.pkey\n"
                                        + "from\n"
                                        + "  postgres_databases pd,\n"
                                        + "  postgres_server_users psu,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  pd.datdba=psu.pkey\n"
                                        + "  and psu.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<pds.size();c++) {
                                        PostgresHandler.removePostgresDatabase(conn, invalidateList, pds.getInt(c));
                                    }
                                }

                                // postgres_users
                                {
                                    List<String> pus=conn.executeStringListQuery( 
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  pu.username\n"
                                        + "from\n"
                                        + "  postgres_users pu,\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  pu.username=un.username\n"
                                        + "  and un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<pus.size();c++) {
                                        PostgresHandler.removePostgresUser(conn, invalidateList, pus.get(c));
                                    }
                                }

                                // sendmail_smtp_stats
                                {
                                    // Those that are part of canceled accounts
                                    if(
                                        conn.executeBooleanQuery(
                                            Connection.TRANSACTION_READ_COMMITTED,
                                            true,
                                            true,
                                            "select\n"
                                            + "  (\n"
                                            + "    select\n"
                                            + "      sss.pkey\n"
                                            + "    from\n"
                                            + "      sendmail_smtp_stats sss,\n"
                                            + "      packages pk,\n"
                                            + "      businesses bu\n"
                                            + "    where\n"
                                            + "      sss.package=pk.name\n"
                                            + "      and pk.accounting=bu.accounting\n"
                                            + "      and bu.canceled is not null\n"
                                            + "      and (?::date-bu.canceled::date)>30\n"
                                            + "    limit 1\n"
                                            + "  ) is not null",
                                            now
                                        )
                                    ) {
                                        conn.executeUpdate(
                                            "delete from\n"
                                            + "  sendmail_smtp_stats\n"
                                            + "where\n"
                                            + "  pkey in (\n"
                                            + "    select\n"
                                            + "      sss.pkey\n"
                                            + "    from\n"
                                            + "      sendmail_smtp_stats sss,\n"
                                            + "      packages pk,\n"
                                            + "      businesses bu\n"
                                            + "    where\n"
                                            + "      sss.package=pk.name\n"
                                            + "      and pk.accounting=bu.accounting\n"
                                            + "      and bu.canceled is not null\n"
                                            + "      and (?::date-bu.canceled::date)>30\n"
                                            + "  )",
                                            now
                                        );
                                        invalidateList.addTable(conn, SchemaTable.TableID.SENDMAIL_SMTP_STATS, InvalidateList.allBusinesses, InvalidateList.allServers, false);
                                    }

                                    // Those that are older than SendmailSmtpStat.MAX_STATISTICS_AGE
                                    if(
                                        conn.executeBooleanQuery(
                                            Connection.TRANSACTION_READ_COMMITTED,
                                            true,
                                            true,
                                            "select\n"
                                            + "  (\n"
                                            + "    select\n"
                                            + "      pkey\n"
                                            + "    from\n"
                                            + "      sendmail_smtp_stats\n"
                                            + "    where\n"
                                            + "      (?::date-date)>"+SendmailSmtpStat.MAX_STATISTICS_AGE+"\n"
                                            + "    limit 1\n"
                                            + "  ) is not null",
                                            now
                                        )
                                    ) {
                                        conn.executeUpdate(
                                            "delete from\n"
                                            + "  sendmail_smtp_stats\n"
                                            + "where\n"
                                            + "  (?::date-date)>"+SendmailSmtpStat.MAX_STATISTICS_AGE,
                                            now
                                        );
                                        invalidateList.addTable(conn, SchemaTable.TableID.SENDMAIL_SMTP_STATS, InvalidateList.allBusinesses, InvalidateList.allServers, false);
                                    }
                                }

                                // usernames
                                // delete all closed usernames, unless used by a business_administrator that was left behind
                                {
                                    List<String> uns=conn.executeStringListQuery( 
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  un.username\n"
                                        + "from\n"
                                        + "  usernames un,\n"
                                        + "  packages pk,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  un.package=pk.name\n"
                                        + "  and pk.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30\n"
                                        + "  and (select ba.username from business_administrators ba where ba.username=un.username) is null",
                                        now
                                    );
                                    for(int c=0;c<uns.size();c++) {
                                        UsernameHandler.removeUsername(conn, invalidateList, uns.get(c));
                                    }
                                }

                                // disable_log
                                {
                                    IntList dls=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  dl.pkey\n"
                                        + "from\n"
                                        + "  disable_log dl,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  dl.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30\n"
                                        + "  and (select ba.username from business_administrators ba where ba.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select bu2.accounting from businesses bu2 where bu2.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select cr.pkey from cvs_repositories cr where cr.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select el.pkey from email_lists el where el.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select ep.pkey from email_pipes ep where ep.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select esr.pkey from email_smtp_relays esr where esr.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select hst.pkey from httpd_shared_tomcats hst where hst.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select hsb.pkey from httpd_site_binds hsb where hsb.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select hs.pkey from httpd_sites hs where hs.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select isu.pkey from interbase_server_users isu where isu.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select iu.username from interbase_users iu where iu.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select la.username from linux_accounts la where la.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select lsa.pkey from linux_server_accounts lsa where lsa.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select msu.pkey from mysql_server_users msu where msu.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select mu.username from mysql_users mu where mu.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select pk.name from packages pk where pk.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select psu.pkey from postgres_server_users psu where psu.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select pu.username from postgres_users pu where pu.disable_log=dl.pkey limit 1) is null\n"
                                        + "  and (select un.username from usernames un where un.disable_log=dl.pkey limit 1) is null",
                                        now
                                    );
                                    for(int c=0;c<dls.size();c++) BusinessHandler.removeDisableLog(conn, invalidateList, dls.getInt(c));
                                }

                                // business_servers
                                // delete all business_servers for canceled businesses
                                {
                                    // non-default
                                    IntList bss=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  bs.pkey\n"
                                        + "from\n"
                                        + "  business_servers bs,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  not bs.is_default\n"
                                        + "  and bs.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<bss.size();c++) BusinessHandler.removeBusinessServer(conn, invalidateList, bss.getInt(c));

                                    // default
                                    bss=conn.executeIntListQuery(
                                        Connection.TRANSACTION_READ_COMMITTED,
                                        true,
                                        "select\n"
                                        + "  bs.pkey\n"
                                        + "from\n"
                                        + "  business_servers bs,\n"
                                        + "  businesses bu\n"
                                        + "where\n"
                                        + "  bs.accounting=bu.accounting\n"
                                        + "  and bu.canceled is not null\n"
                                        + "  and (?::date-bu.canceled::date)>30",
                                        now
                                    );
                                    for(int c=0;c<bss.size();c++) BusinessHandler.removeBusinessServer(conn, invalidateList, bss.getInt(c));
                                }
                                if(message.length()>0) MasterServer.reportWarning(message.toString());
                            } catch(IOException err) {
                                if(conn.rollbackAndClose()) {
                                    connRolledBack=true;
                                    invalidateList=null;
                                }
                                if(backupConn.rollbackAndClose()) {
                                    backupConnRolledBack=true;
                                    invalidateList=null;
                                }
                                throw err;
                            } catch(SQLException err) {
                                if(conn.rollbackAndClose()) {
                                    connRolledBack=true;
                                    invalidateList=null;
                                }
                                if(backupConn.rollbackAndClose()) {
                                    backupConnRolledBack=true;
                                    invalidateList=null;
                                }
                                throw err;
                            } finally {
                                if(!connRolledBack && !conn.isClosed()) conn.commit();
                                if(!backupConnRolledBack && !backupConn.isClosed()) backupConn.commit();
                            }
                        } finally {
                            backupConn.releaseConnection();
                        }
                    } finally {
                        conn.releaseConnection();
                    }
                    if(invalidateList!=null) MasterServer.invalidateTables(invalidateList, null);
                } finally {
                    timer.stop();
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                MasterServer.reportError(T, null);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}