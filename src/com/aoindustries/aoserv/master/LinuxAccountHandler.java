package com.aoindustries.aoserv.master;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServPermission;
import com.aoindustries.aoserv.client.AOServProtocol;
import com.aoindustries.aoserv.client.EmailSpamAssassinIntegrationMode;
import com.aoindustries.aoserv.client.HttpdSharedTomcat;
import com.aoindustries.aoserv.client.HttpdSite;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.LinuxAccountType;
import com.aoindustries.aoserv.client.LinuxGroup;
import com.aoindustries.aoserv.client.LinuxGroupAccount;
import com.aoindustries.aoserv.client.LinuxServerAccount;
import com.aoindustries.aoserv.client.MasterUser;
import com.aoindustries.aoserv.client.PasswordChecker;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.util.IntList;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The <code>LinuxAccountHandler</code> handles all the accesses to the Linux tables.
 *
 * @author  AO Industries, Inc.
 */
final public class LinuxAccountHandler {

    private LinuxAccountHandler() {
    }

    private final static Map<String,Boolean> disabledLinuxAccounts=new HashMap<String,Boolean>();
    private final static Map<Integer,Boolean> disabledLinuxServerAccounts=new HashMap<Integer,Boolean>();

    public static void checkAccessLinuxAccount(DatabaseConnection conn, RequestSource source, String action, String username) throws IOException, SQLException {
        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
        if(mu!=null) {
            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                IntList lsas = getLinuxServerAccountsForLinuxAccount(conn, username);
                boolean found = false;
                for(Integer lsa : lsas) {
                    if(ServerHandler.canAccessServer(conn, source, getAOServerForLinuxServerAccount(conn, lsa.intValue()))) {
                        found=true;
                        break;
                    }
                }
                if(!found) {
                    String message=
                        "business_administrator.username="
                        +source.getUsername()
                        +" is not allowed to access linux_account: action='"
                        +action
                        +", username="
                        +username
                    ;
                    throw new SQLException(message);
                }
            }
        } else {
            UsernameHandler.checkAccessUsername(conn, source, action, username);
        }
    }

    public static void checkAccessLinuxGroup(DatabaseConnection conn, RequestSource source, String action, String name) throws IOException, SQLException {
        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
        if(mu!=null) {
            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                IntList lsgs = getLinuxServerGroupsForLinuxGroup(conn, name);
                boolean found = false;
                for(int lsg : lsgs) {
                    if(ServerHandler.canAccessServer(conn, source, getAOServerForLinuxServerGroup(conn, lsg))) {
                        found=true;
                        break;
                    }
                }
                if(!found) {
                    String message=
                        "business_administrator.username="
                        +source.getUsername()
                        +" is not allowed to access linux_group: action='"
                        +action
                        +", name="
                        +name
                    ;
                    throw new SQLException(message);
                }
            }
        } else {
            PackageHandler.checkAccessPackage(conn, source, action, getPackageForLinuxGroup(conn, name));
        }
    }

    public static void checkAccessLinuxGroupAccount(DatabaseConnection conn, RequestSource source, String action, int pkey) throws IOException, SQLException {
        checkAccessLinuxAccount(conn, source, action, getLinuxAccountForLinuxGroupAccount(conn, pkey));
        checkAccessLinuxGroup(conn, source, action, getLinuxGroupForLinuxGroupAccount(conn, pkey));
    }

    public static boolean canAccessLinuxServerAccount(DatabaseConnection conn, RequestSource source, int account) throws IOException, SQLException {
        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
        if(mu!=null) {
            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                return ServerHandler.canAccessServer(conn, source, getAOServerForLinuxServerAccount(conn, account));
            } else return true;
        } else {
            return UsernameHandler.canAccessUsername(conn, source, getUsernameForLinuxServerAccount(conn, account));
        }
    }

    public static void checkAccessLinuxServerAccount(DatabaseConnection conn, RequestSource source, String action, int account) throws IOException, SQLException {
        if(!canAccessLinuxServerAccount(conn, source, account)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" is not allowed to access linux_server_account: action='"
                +action
                +", pkey="
                +account
            ;
            throw new SQLException(message);
        }
    }

    public static boolean canAccessLinuxServerGroup(DatabaseConnection conn, RequestSource source, int group) throws IOException, SQLException {
        return
            PackageHandler.canAccessPackage(conn, source, getPackageForLinuxServerGroup(conn, group))
            && ServerHandler.canAccessServer(conn, source, getAOServerForLinuxServerGroup(conn, group))
        ;
    }

    public static void checkAccessLinuxServerGroup(DatabaseConnection conn, RequestSource source, String action, int group) throws IOException, SQLException {
        if(!canAccessLinuxServerGroup(conn, source, group)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" is not allowed to access linux_server_group: action='"
                +action
                +", pkey="
                +group
            ;
            throw new SQLException(message);
        }
    }

    /**
     * Adds a linux account.
     */
    public static void addLinuxAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String primary_group,
        String name,
        String office_location,
        String office_phone,
        String home_phone,
        String type,
        String shell,
        boolean skipSecurityChecks
    ) throws IOException, SQLException {
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to add LinuxAccount named '"+LinuxAccount.MAIL+'\'');
        if(!LinuxAccount.isValidUsername(username)) throw new SQLException("Invalid LinuxAccount username: "+username);
        String validity;
        if((validity=LinuxAccount.checkGECOS(name, "full name"))!=null) throw new SQLException(validity);
        if((validity=LinuxAccount.checkGECOS(office_location, "location"))!=null) throw new SQLException(validity);
        if((validity=LinuxAccount.checkGECOS(office_phone, "office phone"))!=null) throw new SQLException(validity);
        if((validity=LinuxAccount.checkGECOS(home_phone, "home phone"))!=null) throw new SQLException(validity);

        // Make sure the shell is allowed for the type of account being added
        if(!LinuxAccountType.isAllowedShell(type, shell)) throw new SQLException("shell='"+shell+"' not allowed for type='"+type+'\'');

        if(!skipSecurityChecks) {
            UsernameHandler.checkAccessUsername(conn, source, "addLinuxAccount", username);
            if(UsernameHandler.isUsernameDisabled(conn, username)) throw new SQLException("Unable to add LinuxAccount, Username disabled: "+username);
        }

        conn.executeUpdate(
            "insert into linux_accounts values(?,?,?,?,?,?,?,now(),null)",
            username,
            name,
            office_location,
            office_phone,
            home_phone,
            type,
            shell
        );
        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_ACCOUNTS,
            UsernameHandler.getBusinessForUsername(conn, username),
            InvalidateList.allServers,
            false
        );

        addLinuxGroupAccount(
            conn,
            source,
            invalidateList,
            primary_group,
            username,
            true,
            skipSecurityChecks
        );
    }

    public static void addLinuxGroup(
        DatabaseConnection conn,
        RequestSource source, 
        InvalidateList invalidateList,
        String groupName, 
        String packageName, 
        String type,
        boolean skipSecurityChecks
    ) throws IOException, SQLException {
        if(!skipSecurityChecks) {
            PackageHandler.checkAccessPackage(conn, source, "addLinuxGroup", packageName);
            if(PackageHandler.isPackageDisabled(conn, packageName)) throw new SQLException("Unable to add LinuxGroup, Package disabled: "+packageName);
        }
        if(!LinuxGroup.isValidGroupname(groupName)) throw new SQLException("Invalid Linux Group name: "+groupName);
        if (
            groupName.equals(LinuxGroup.FTPONLY)
            || groupName.equals(LinuxGroup.MAIL)
            || groupName.equals(LinuxGroup.MAILONLY)
        ) throw new SQLException("Not allowed to add LinuxGroup: "+groupName);

        conn.executeUpdate("insert into linux_groups values(?,?,?)", groupName, packageName, type);

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_GROUPS,
            PackageHandler.getBusinessForPackage(conn, packageName),
            InvalidateList.allServers,
            false
        );
    }

    public static int addLinuxGroupAccount(
        DatabaseConnection conn,
        RequestSource source, 
        InvalidateList invalidateList,
        String groupName, 
        String username, 
        boolean isPrimary,
        boolean skipSecurityChecks
    ) throws IOException, SQLException {
        if(groupName.equals(LinuxGroup.MAIL)) throw new SQLException("Not allowed to add LinuxGroupAccount for group '"+LinuxGroup.MAIL+'\'');
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to add LinuxGroupAccount for user '"+LinuxAccount.MAIL+'\'');
        if(!skipSecurityChecks) {
            if(
                !groupName.equals(LinuxGroup.FTPONLY)
                && !groupName.equals(LinuxGroup.MAILONLY)
            ) checkAccessLinuxGroup(conn, source, "addLinuxGroupAccount", groupName);
            checkAccessLinuxAccount(conn, source, "addLinuxGroupAccount", username);
            if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to add LinuxGroupAccount, LinuxAccount disabled: "+username);
        }
        if(groupName.equals(LinuxGroup.FTPONLY)) {
            // Only allowed to have ftponly group when it is a ftponly account
            String type=getTypeForLinuxAccount(conn, username);
            if(!type.equals(LinuxAccountType.FTPONLY)) throw new SQLException("Not allowed to add LinuxGroupAccount for group '"+LinuxGroup.FTPONLY+"' on non-ftp-only-type LinuxAccount named "+username);
        }
        if(groupName.equals(LinuxGroup.MAILONLY)) {
            // Only allowed to have mail group when it is a "mailonly" account
            String type=getTypeForLinuxAccount(conn, username);
            if(!type.equals(LinuxAccountType.EMAIL)) throw new SQLException("Not allowed to add LinuxGroupAccount for group '"+LinuxGroup.MAILONLY+"' on non-email-type LinuxAccount named "+username);
        }

        // Do not allow more than 31 groups per account
        int count=conn.executeIntQuery("select count(*) from linux_group_accounts where username=?", username);
        if(count>=LinuxGroupAccount.MAX_GROUPS) throw new SQLException("Only "+LinuxGroupAccount.MAX_GROUPS+" groups are allowed per user, username="+username+" already has access to "+count+" groups");

        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('linux_group_accounts_pkey_seq')");

        conn.executeUpdate(
            "insert into linux_group_accounts values(?,?,?,?)",
            pkey,
            groupName,
            username,
            isPrimary
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_GROUP_ACCOUNTS,
            InvalidateList.getCollection(
                UsernameHandler.getBusinessForUsername(conn, username),
                getBusinessForLinuxGroup(conn, groupName)
            ),
            getAOServersForLinuxGroupAccount(conn, pkey),
            false
        );
        return pkey;
    }

    public static int addLinuxServerAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        int aoServer,
        String home,
        boolean skipSecurityChecks
    ) throws IOException, SQLException {
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to add LinuxServerAccount for user '"+LinuxAccount.MAIL+'\'');
        if(!skipSecurityChecks) {
            checkAccessLinuxAccount(conn, source, "addLinuxServerAccount", username);
            if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to add LinuxServerAccount, LinuxAccount disabled: "+username);
            ServerHandler.checkAccessServer(conn, source, "addLinuxServerAccount", aoServer);
            UsernameHandler.checkUsernameAccessServer(conn, source, "addLinuxServerAccount", username, aoServer);
        }
        if(!home.equals(LinuxServerAccount.getDefaultHomeDirectory(username))) {
            // Must be in /www/... or /wwwgroup/...
            if(
                (home.length()<=(HttpdSite.WWW_DIRECTORY.length()+1) || !home.substring(0, HttpdSite.WWW_DIRECTORY.length()+1).equals(HttpdSite.WWW_DIRECTORY+'/'))
                && (home.length()<=(HttpdSharedTomcat.WWW_GROUP_DIR.length()+1) || !home.substring(0, HttpdSharedTomcat.WWW_GROUP_DIR.length()+1).equals(HttpdSharedTomcat.WWW_GROUP_DIR+'/'))
            ) throw new SQLException("Invalid home directory: "+home);

            if(home.length()>(HttpdSite.WWW_DIRECTORY.length()+1) && home.substring(0, HttpdSite.WWW_DIRECTORY.length()+1).equals(HttpdSite.WWW_DIRECTORY+'/')) {
                // May also be in /www/(sitename)/webapps
                String rh=home.substring(HttpdSite.WWW_DIRECTORY.length()+1);
                if(rh.length()>8 && rh.substring(rh.length()-8).equals("/webapps")) rh=rh.substring(0, rh.length()-8);
                // May be in /www/(sitename)
                if(!HttpdSite.isValidSiteName(rh)) throw new SQLException("Invalid site name for www home directory: "+home);
            }

            if(home.length()>(HttpdSharedTomcat.WWW_GROUP_DIR.length()+1) && home.substring(0, HttpdSharedTomcat.WWW_GROUP_DIR.length()+1).equals(HttpdSharedTomcat.WWW_GROUP_DIR+'/')) {
                // May also be in /www/(sitename)/webapps
                String rh=home.substring(HttpdSharedTomcat.WWW_GROUP_DIR.length()+1);
                // May be in /wwwgroup/(sitename)
                if(!HttpdSharedTomcat.isValidSharedTomcatName(rh)) throw new SQLException("Invalid shared tomcat name for wwwgroup home directory: "+home);
            }
        }

        // The primary group for this user must exist on this server
        String primaryGroup=getPrimaryLinuxGroup(conn, username);
        int primaryLSG=getLinuxServerGroup(conn, primaryGroup, aoServer);
        if(primaryLSG<0) throw new SQLException("Unable to find primary Linux group '"+primaryGroup+"' on AOServer #"+aoServer+" for Linux account '"+username+"'");

        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('linux_server_accounts_pkey_seq')");
        // Now allocating unique to entire system for server portability between farms
        //String farm=ServerHandler.getFarmForServer(conn, aoServer);
        conn.executeUpdate(
            "insert into\n"
            + "  linux_server_accounts\n"
            + "values(\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  (\n"
            + "    select\n"
            + "      li.id\n"
            + "    from\n"
            + "      linux_ids li\n"
            + "    where\n"
            + "      not li.is_system\n"
            + "      and (\n"
            + "        select\n"
            + "          lsa.pkey\n"
            + "        from\n"
            + "          linux_server_accounts lsa,\n"
            + "          servers se\n"
            + "        where\n"
            + "          lsa.ao_server=se.pkey\n"
            //+ "          and se.farm=?\n"
            + "          and li.id=lsa.uid\n"
            + "        limit 1\n"
            + "      ) is null\n"
            + "    order by\n"
            + "      id\n"
            + "    limit 1\n"
            + "  ),\n"
            + "  ?,\n"
            + "  null,\n"
            + "  null,\n"
            + "  null,\n"
            + "  false,\n"
            + "  null,\n"
            + "  null,\n"
            + "  now(),\n"
            + "  true,\n"
            + "  "+LinuxServerAccount.DEFAULT_TRASH_EMAIL_RETENTION+",\n"
            + "  "+LinuxServerAccount.DEFAULT_JUNK_EMAIL_RETENTION+",\n"
            + "  ?,\n"
            + "  "+LinuxServerAccount.DEFAULT_SPAM_ASSASSIN_REQUIRED_SCORE+",\n"
            + "  "+LinuxServerAccount.DEFAULT_SPAM_ASSASSIN_DISCARD_SCORE+"\n"
            + ")",
            pkey,
            username,
            aoServer,
            //farm,
            home,
            EmailSpamAssassinIntegrationMode.DEFAULT_SPAMASSASSIN_INTEGRATION_MODE
        );
        // Notify all clients of the update
        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            accounting,
            aoServer,
            true
        );
        // If it is a email type, add the default attachment blocks
        if(isLinuxAccountEmailType(conn, username)) {
            conn.executeUpdate(
                "insert into\n"
                + "  email_attachment_blocks\n"
                + "select\n"
                + "  nextval('email_attachment_blocks_pkey_seq'),\n"
                + "  ?,\n"
                + "  extension\n"
                + "from\n"
                + "  email_attachment_types\n"
                + "where\n"
                + "  is_default_block",
                pkey
            );
            invalidateList.addTable(
                conn,
                SchemaTable.TableID.EMAIL_ATTACHMENT_BLOCKS,
                accounting,
                aoServer,
                false
            );
        }
        return pkey;
    }

    public static int addLinuxServerGroup(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String groupName,
        int aoServer,
        boolean skipSecurityChecks
    ) throws IOException, SQLException {
        if(
            groupName.equals(LinuxGroup.FTPONLY)
            || groupName.equals(LinuxGroup.MAIL)
            || groupName.equals(LinuxGroup.MAILONLY)
        ) throw new SQLException("Not allowed to add LinuxServerGroup for group '"+groupName+'\'');
        String accounting=getBusinessForLinuxGroup(conn, groupName);
        if(!skipSecurityChecks) {
            checkAccessLinuxGroup(conn, source, "addLinuxServerGroup", groupName);
            ServerHandler.checkAccessServer(conn, source, "addLinuxServerGroup", aoServer);
            checkLinuxGroupAccessServer(conn, source, "addLinuxServerGroup", groupName, aoServer);
            BusinessHandler.checkBusinessAccessServer(conn, source, "addLinuxServerGroup", accounting, aoServer);
        }

        // Now allocating unique to entire system for server portability between farms
        //String farm=ServerHandler.getFarmForServer(conn, aoServer);
        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('linux_server_groups_pkey_seq')");
        conn.executeUpdate(
            "insert into\n"
            + "  linux_server_groups\n"
            + "values(\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  (\n"
            + "    select\n"
            + "      li.id\n"
            + "    from\n"
            + "      linux_ids li\n"
            + "    where\n"
            + "      not li.is_system\n"
            + "      and (\n"
            + "        select\n"
            + "          lsg.pkey\n"
            + "        from\n"
            + "          linux_server_groups lsg,\n"
            + "          servers se\n"
            + "        where\n"
            + "          lsg.ao_server=se.pkey\n"
            //+ "          and se.farm=?\n"
            + "          and li.id=lsg.gid\n"
            + "        limit 1\n"
            + "      ) is null\n"
            + "      and (\n"
            + "        select\n"
            + "          ffr.pkey\n"
            + "        from\n"
            + "          failover_file_replications ffr\n"
            + "        where\n"
            + "          ffr.quota_gid=li.id\n"
            + "        limit 1\n"
            + "      ) is null\n"
            + "    order by\n"
            + "      id\n"
            + "    limit 1\n"
            + "  ),\n"
            + "  now()\n"
            + ")",
            pkey,
            groupName,
            aoServer//,
            //farm
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_GROUPS,
            accounting,
            aoServer,
            true
        );
        return pkey;
    }

    /**
     * Copies the contents of a home directory from one server to another.
     */
    public static long copyHomeDirectory(
        DatabaseConnection conn,
        RequestSource source,
        int from_lsa,
        int to_server
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "copyHomeDirectory", from_lsa);
        String username=getUsernameForLinuxServerAccount(conn, from_lsa);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to copy LinuxAccount named '"+LinuxAccount.MAIL+'\'');
        int from_server=getAOServerForLinuxServerAccount(conn, from_lsa);
        int to_lsa=conn.executeIntQuery(
            "select pkey from linux_server_accounts where username=? and ao_server=?",
            username,
            to_server
        );
        checkAccessLinuxServerAccount(conn, source, "copyHomeDirectory", to_lsa);
        String type=getTypeForLinuxAccount(conn, username);
        if(
            !type.equals(LinuxAccountType.USER)
            && !type.equals(LinuxAccountType.EMAIL)
            && !type.equals(LinuxAccountType.FTPONLY)
        ) throw new SQLException("Not allowed to copy LinuxAccounts of type '"+type+"', username="+username);

        long byteCount=DaemonHandler.getDaemonConnector(conn, from_server).copyHomeDirectory(username, DaemonHandler.getDaemonConnector(conn, to_server));
        return byteCount;
    }

    /**
     * Copies a password from one linux account to another
     */
    public static void copyLinuxServerAccountPassword(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int from_lsa,
        int to_lsa
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "copyLinuxServerAccountPassword", from_lsa);
        if(isLinuxServerAccountDisabled(conn, from_lsa)) throw new SQLException("Unable to copy LinuxServerAccount password, from account disabled: "+from_lsa);
        String from_username=getUsernameForLinuxServerAccount(conn, from_lsa);
        if(from_username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to copy the password from LinuxAccount named '"+LinuxAccount.MAIL+'\'');
        checkAccessLinuxServerAccount(conn, source, "copyLinuxServerAccountPassword", to_lsa);
        if(isLinuxServerAccountDisabled(conn, to_lsa)) throw new SQLException("Unable to copy LinuxServerAccount password, to account disabled: "+to_lsa);
        String to_username=getUsernameForLinuxServerAccount(conn, to_lsa);
        if(to_username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to copy the password to LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        int from_server=getAOServerForLinuxServerAccount(conn, from_lsa);
        int to_server=getAOServerForLinuxServerAccount(conn, to_lsa);

        String from_type=getTypeForLinuxAccount(conn, from_username);
        if(
            !from_type.equals(LinuxAccountType.APPLICATION)
            && !from_type.equals(LinuxAccountType.USER)
            && !from_type.equals(LinuxAccountType.EMAIL)
            && !from_type.equals(LinuxAccountType.FTPONLY)
        ) throw new SQLException("Not allowed to copy passwords from LinuxAccounts of type '"+from_type+"', username="+from_username);

        String to_type=getTypeForLinuxAccount(conn, to_username);
        if(
            !to_type.equals(LinuxAccountType.APPLICATION)
            && !to_type.equals(LinuxAccountType.USER)
            && !to_type.equals(LinuxAccountType.EMAIL)
            && !to_type.equals(LinuxAccountType.FTPONLY)
        ) throw new SQLException("Not allowed to copy passwords to LinuxAccounts of type '"+to_type+"', username="+to_username);

        String enc_password=DaemonHandler.getDaemonConnector(conn, from_server).getEncryptedLinuxAccountPassword(from_username);
        DaemonHandler.getDaemonConnector(conn, to_server).setEncryptedLinuxAccountPassword(to_username, enc_password);

        String from_accounting=UsernameHandler.getBusinessForUsername(conn, from_username);
        String to_accounting=UsernameHandler.getBusinessForUsername(conn, to_username);
    }

    public static void disableLinuxAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int disableLog,
        String username
    ) throws IOException, SQLException {
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("LinuxAccount is already disabled: "+username);
        BusinessHandler.checkAccessDisableLog(conn, source, "disableLinuxAccount", disableLog, false);
        checkAccessLinuxAccount(conn, source, "disableLinuxAccount", username);
        IntList lsas=getLinuxServerAccountsForLinuxAccount(conn, username);
        for(int c=0;c<lsas.size();c++) {
            int lsa=lsas.getInt(c);
            if(!isLinuxServerAccountDisabled(conn, lsa)) {
                throw new SQLException("Cannot disable LinuxAccount '"+username+"': LinuxServerAccount not disabled: "+lsa);
            }
        }

        conn.executeUpdate(
            "update linux_accounts set disable_log=? where username=?",
            disableLog,
            username
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_ACCOUNTS,
            UsernameHandler.getBusinessForUsername(conn, username),
            UsernameHandler.getServersForUsername(conn, username),
            false
        );
    }

    public static void disableLinuxServerAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int disableLog,
        int pkey
    ) throws IOException, SQLException {
        if(isLinuxServerAccountDisabled(conn, pkey)) throw new SQLException("LinuxServerAccount is already disabled: "+pkey);
        BusinessHandler.checkAccessDisableLog(conn, source, "disableLinuxServerAccount", disableLog, false);
        checkAccessLinuxServerAccount(conn, source, "disableLinuxServerAccount", pkey);

        // The UID must be a user UID
        int uid=getUIDForLinuxServerAccount(conn, pkey);
        if(uid<UnixFile.MINIMUM_USER_UID) throw new SQLException("Not allowed to remove a system LinuxServerAccount: pkey="+pkey+", uid="+uid);

        IntList crs=CvsHandler.getCvsRepositoriesForLinuxServerAccount(conn, pkey);
        for(int c=0;c<crs.size();c++) {
            int cr=crs.getInt(c);
            if(!CvsHandler.isCvsRepositoryDisabled(conn, cr)) {
                throw new SQLException("Cannot disable LinuxServerAccount #"+pkey+": CvsRepository not disabled: "+cr);
            }
        }
        IntList hsts=HttpdHandler.getHttpdSharedTomcatsForLinuxServerAccount(conn, pkey);
        for(int c=0;c<hsts.size();c++) {
            int hst=hsts.getInt(c);
            if(!HttpdHandler.isHttpdSharedTomcatDisabled(conn, hst)) {
                throw new SQLException("Cannot disable LinuxServerAccount #"+pkey+": HttpdSharedTomcat not disabled: "+hst);
            }
        }
        IntList hss=HttpdHandler.getHttpdSitesForLinuxServerAccount(conn, pkey);
        for(int c=0;c<hss.size();c++) {
            int hs=hss.getInt(c);
            if(!HttpdHandler.isHttpdSiteDisabled(conn, hs)) {
                throw new SQLException("Cannot disable LinuxServerAccount #"+pkey+": HttpdSite not disabled: "+hs);
            }
        }
        IntList els=EmailHandler.getEmailListsForLinuxServerAccount(conn, pkey);
        for(int c=0;c<els.size();c++) {
            int el=els.getInt(c);
            if(!EmailHandler.isEmailListDisabled(conn, el)) {
                throw new SQLException("Cannot disable LinuxServerAccount #"+pkey+": EmailList not disabled: "+el);
            }
        }

        conn.executeUpdate(
            "update linux_server_accounts set disable_log=? where pkey=?",
            disableLog,
            pkey
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    public static void enableLinuxAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username
    ) throws IOException, SQLException {
        int disableLog=getDisableLogForLinuxAccount(conn, username);
        if(disableLog==-1) throw new SQLException("LinuxAccount is already enabled: "+username);
        BusinessHandler.checkAccessDisableLog(conn, source, "enableLinuxAccount", disableLog, true);
        checkAccessLinuxAccount(conn, source, "enableLinuxAccount", username);
        if(UsernameHandler.isUsernameDisabled(conn, username)) throw new SQLException("Unable to enable LinuxAccount '"+username+"', Username not enabled: "+username);

        conn.executeUpdate(
            "update linux_accounts set disable_log=null where username=?",
            username
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_ACCOUNTS,
            UsernameHandler.getBusinessForUsername(conn, username),
            UsernameHandler.getServersForUsername(conn, username),
            false
        );
    }

    public static void enableLinuxServerAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        int disableLog=getDisableLogForLinuxServerAccount(conn, pkey);
        if(disableLog==-1) throw new SQLException("LinuxServerAccount is already enabled: "+pkey);
        BusinessHandler.checkAccessDisableLog(conn, source, "enableLinuxServerAccount", disableLog, true);
        checkAccessLinuxServerAccount(conn, source, "enableLinuxServerAccount", pkey);
        String la=getUsernameForLinuxServerAccount(conn, pkey);
        if(isLinuxAccountDisabled(conn, la)) throw new SQLException("Unable to enable LinuxServerAccount #"+pkey+", LinuxAccount not enabled: "+la);

        conn.executeUpdate(
            "update linux_server_accounts set disable_log=null where pkey=?",
            pkey
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            UsernameHandler.getBusinessForUsername(conn, la),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    /**
     * Gets the contents of an autoresponder.
     */
    public static String getAutoresponderContent(DatabaseConnection conn, RequestSource source, int lsa) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "getAutoresponderContent", lsa);
        String username=getUsernameForLinuxServerAccount(conn, lsa);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to get the autoresponder content for LinuxAccount named '"+LinuxAccount.MAIL+'\'');
        String path=conn.executeStringQuery("select coalesce(autoresponder_path, '') from linux_server_accounts where pkey=?", lsa);
        String content;
        if(path.length()==0) content="";
        else {
            int aoServer=getAOServerForLinuxServerAccount(conn, lsa);
            content=DaemonHandler.getDaemonConnector(conn, aoServer).getAutoresponderContent(path);
        }
        return content;
    }

    /**
     * Gets the contents of a user cron table.
     */
    public static String getCronTable(DatabaseConnection conn, RequestSource source, int lsa) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "getCronTable", lsa);
        String username=getUsernameForLinuxServerAccount(conn, lsa);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to get the cron table for LinuxAccount named '"+LinuxAccount.MAIL+'\'');
        String type=getTypeForLinuxAccount(conn, username);
        if(
            !type.equals(LinuxAccountType.USER)
        ) throw new SQLException("Not allowed to get the cron table for LinuxAccounts of type '"+type+"', username="+username);
        int aoServer=getAOServerForLinuxServerAccount(conn, lsa);

        return DaemonHandler.getDaemonConnector(conn, aoServer).getCronTable(username);
    }

    public static int getDisableLogForLinuxAccount(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeIntQuery("select coalesce(disable_log, -1) from linux_accounts where username=?", username);
    }

    public static int getDisableLogForLinuxServerAccount(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select coalesce(disable_log, -1) from linux_server_accounts where pkey=?", pkey);
    }

    public static void invalidateTable(SchemaTable.TableID tableID) {
        if(tableID==SchemaTable.TableID.LINUX_ACCOUNTS) {
            synchronized(LinuxAccountHandler.class) {
                disabledLinuxAccounts.clear();
            }
        } else if(tableID==SchemaTable.TableID.LINUX_SERVER_ACCOUNTS) {
            synchronized(LinuxAccountHandler.class) {
                disabledLinuxServerAccounts.clear();
            }
        }
    }

    public static boolean isLinuxAccount(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            "select\n"
            + "  (\n"
            + "    select\n"
            + "      username\n"
            + "    from\n"
            + "      linux_accounts\n"
            + "    where\n"
            + "      username=?\n"
            + "    limit 1\n"
            + "  ) is not null",
            username
        );
    }

    public static boolean isLinuxAccountDisabled(DatabaseConnection conn, String username) throws IOException, SQLException {
	    synchronized(LinuxAccountHandler.class) {
            Boolean O=disabledLinuxAccounts.get(username);
            if(O!=null) return O.booleanValue();
            boolean isDisabled=getDisableLogForLinuxAccount(conn, username)!=-1;
            disabledLinuxAccounts.put(username, isDisabled);
            return isDisabled;
	    }
    }

    public static boolean isLinuxAccountEmailType(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            "select\n"
            + "  lat.is_email\n"
            + "from\n"
            + "  linux_accounts la,\n"
            + "  linux_account_types lat\n"
            + "where\n"
            + "  la.username=?\n"
            + "  and la.type=lat.name",
            username
        );
    }

    public static boolean isLinuxServerAccountDisabled(DatabaseConnection conn, int pkey) throws IOException, SQLException {
	    synchronized(LinuxAccountHandler.class) {
            Integer I=Integer.valueOf(pkey);
            Boolean O=disabledLinuxServerAccounts.get(I);
            if(O!=null) return O.booleanValue();
            boolean isDisabled=getDisableLogForLinuxServerAccount(conn, pkey)!=-1;
            disabledLinuxServerAccounts.put(I, isDisabled);
            return isDisabled;
	    }
    }

    public static boolean isLinuxGroupNameAvailable(DatabaseConnection conn, String groupname) throws IOException, SQLException {
        return conn.executeBooleanQuery("select (select name from linux_groups where name=?) is null", groupname);
    }

    public static int getLinuxServerGroup(DatabaseConnection conn, String group, int aoServer) throws IOException, SQLException {
        int pkey=conn.executeIntQuery("select coalesce((select pkey from linux_server_groups where name=? and ao_server=?), -1)", group, aoServer);
        if(pkey==-1) throw new SQLException("Unable to find LinuxServerGroup "+group+" on "+aoServer);
        return pkey;
    }

    public static String getPrimaryLinuxGroup(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeStringQuery("select group_name from linux_group_accounts where username=? and is_primary", username);
    }

    public static boolean isLinuxServerAccountPasswordSet(
        DatabaseConnection conn,
        RequestSource source, 
        int account
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "isLinuxServerAccountPasswordSet", account);
        String username=getUsernameForLinuxServerAccount(conn, account);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to check if a password is set for LinuxServerAccount '"+LinuxAccount.MAIL+'\'');

        int aoServer=getAOServerForLinuxServerAccount(conn, account);
        String crypted=DaemonHandler.getDaemonConnector(conn, aoServer).getEncryptedLinuxAccountPassword(username);
        return !LinuxAccount.NO_PASSWORD_CONFIG_VALUE.equals(crypted);
    }

    public static int isLinuxServerAccountProcmailManual(
        DatabaseConnection conn,
        RequestSource source, 
        int account
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "isLinuxServerAccountProcmailManual", account);

        int aoServer=getAOServerForLinuxServerAccount(conn, account);
        if(DaemonHandler.isDaemonAvailable(aoServer)) {
            try {
                return DaemonHandler.getDaemonConnector(conn, aoServer).isProcmailManual(account) ? AOServProtocol.TRUE : AOServProtocol.FALSE;
            } catch(IOException err) {
                DaemonHandler.flagDaemonAsDown(aoServer);
                return AOServProtocol.SERVER_DOWN;
            }
        } else {
            return AOServProtocol.SERVER_DOWN;
        }
    }

    public static void removeLinuxAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username
    ) throws IOException, SQLException {
        checkAccessLinuxAccount(conn, source, "removeLinuxAccount", username);

        removeLinuxAccount(conn, invalidateList, username);
    }

    public static void removeLinuxAccount(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        String username
    ) throws IOException, SQLException {
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to remove LinuxAccount with username '"+LinuxAccount.MAIL+'\'');

        // Detach the linux account from its autoresponder address
        IntList aoServers=getAOServersForLinuxAccount(conn, username);
        for(int c=0;c<aoServers.size();c++) {
            int aoServer=aoServers.getInt(c);
            conn.executeUpdate("update linux_server_accounts set autoresponder_from=null where username=? and ao_server=?", username, aoServer);
        }
        // Delete any FTP guest user info attached to this account
        boolean ftpModified=conn.executeIntQuery("select count(*) from ftp_guest_users where username=?", username)>0;
        if(ftpModified) conn.executeUpdate("delete from ftp_guest_users where username=?", username);
        // Delete the account from all servers
        // Get the values for later use
        for(int c=0;c<aoServers.size();c++) {
            int aoServer=aoServers.getInt(c);
            int pkey=conn.executeIntQuery("select pkey from linux_server_accounts where username=? and ao_server=?", username, aoServer);
            removeLinuxServerAccount(conn, invalidateList, pkey);
        }
        // Delete the group relations for this account
        boolean groupAccountModified=conn.executeIntQuery("select count(*) from linux_group_accounts where username=? limit 1", username)>0;
        if(groupAccountModified) conn.executeUpdate("delete from linux_group_accounts where username=?", username);
        // Delete from the database
        conn.executeUpdate("delete from linux_accounts where username=?", username);

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);

        if(ftpModified) invalidateList.addTable(conn, SchemaTable.TableID.FTP_GUEST_USERS, accounting, aoServers, false);
        if(groupAccountModified) invalidateList.addTable(conn, SchemaTable.TableID.LINUX_GROUP_ACCOUNTS, accounting, aoServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACCOUNTS, accounting, aoServers, false);
    }

    public static void removeLinuxGroup(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String name
    ) throws IOException, SQLException {
        checkAccessLinuxGroup(conn, source, "removeLinuxGroup", name);

        removeLinuxGroup(conn, invalidateList, name);
    }

    public static void removeLinuxGroup(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        String name
    ) throws IOException, SQLException {
        if(
            name.equals(LinuxGroup.FTPONLY)
            || name.equals(LinuxGroup.MAIL)
            || name.equals(LinuxGroup.MAILONLY)
        ) throw new SQLException("Not allowed to remove LinuxGroup named '"+name+"'");

        // Must not be the primary group for any LinuxAccount
        int primaryCount=conn.executeIntQuery("select count(*) from linux_group_accounts where group_name=? and is_primary", name);
        if(primaryCount>0) throw new SQLException("linux_group.name="+name+" is the primary group for "+primaryCount+" Linux "+(primaryCount==1?"account":"accounts"));
        // Get the values for later use
        String accounting=getBusinessForLinuxGroup(conn, name);
        IntList aoServers=getAOServersForLinuxGroup(conn, name);
        for(int c=0;c<aoServers.size();c++) {
            int aoServer=aoServers.getInt(c);
            conn.executeUpdate("delete from linux_server_groups where name=? and ao_server=?", name, aoServer);
        }
        // Delete the group relations for this group
        boolean groupAccountsModified=conn.executeIntQuery("select count(*) from linux_group_accounts where group_name=? limit 1", name)>0;
        if(groupAccountsModified) conn.executeUpdate("delete from linux_group_accounts where group_name=?", name);
        // Delete from the database
        conn.executeUpdate("delete from linux_groups where name=?", name);

        // Notify all clients of the update
        if(aoServers.size()>0) invalidateList.addTable(conn, SchemaTable.TableID.LINUX_SERVER_GROUPS, accounting, aoServers, false);
        if(groupAccountsModified) invalidateList.addTable(conn, SchemaTable.TableID.LINUX_GROUP_ACCOUNTS, accounting, aoServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_GROUPS, accounting, aoServers, false);
    }

    public static void removeLinuxGroupAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        checkAccessLinuxGroupAccount(conn, source, "removeLinuxGroupAccount", pkey);

        // Must not be a primary group
        boolean isPrimary=conn.executeBooleanQuery("select is_primary from linux_group_accounts where pkey=?", pkey);
        if(isPrimary) throw new SQLException("linux_group_accounts.pkey="+pkey+" is a primary group");

        // Must be needingful not by HttpdTomcatSharedSite to be tying to HttpdSharedTomcat please
        int useCount = conn.executeIntQuery(
            "select count(*) from linux_group_accounts lga, "+
                    "linux_server_accounts lsa, "+
                    "httpd_shared_tomcats hst, "+
                    "httpd_tomcat_shared_sites htss, "+
                    "httpd_sites hs "+
                        "where lga.username = lsa.username and "+
                        "lsa.pkey           = hst.linux_server_account and "+
                        "htss.tomcat_site   = hs.pkey and "+
                        "lga.group_name     = hs.linux_group and "+
                        "hst.pkey           = htss.httpd_shared_tomcat and "+
                        "lga.pkey = ?",
            pkey
        );
        if (useCount==0) {
            useCount = conn.executeIntQuery(
                "select count(*) from linux_group_accounts lga, "+
                        "linux_server_groups lsg, "+
                        "httpd_shared_tomcats hst, "+
                        "httpd_tomcat_shared_sites htss, "+
                        "httpd_sites hs "+
                            "where lga.group_name = lsg.name and "+
                            "lsg.pkey             = hst.linux_server_group and "+
                            "htss.tomcat_site     = hs.pkey and "+
                            "lga.username         = hs.linux_account and "+
                            "hst.pkey           = htss.httpd_shared_tomcat and "+
                            "lga.pkey = ?",
                pkey
            );
        }
        if (useCount>0) throw new SQLException("linux_group_account("+pkey+") has been used by "+useCount+" httpd_tomcat_shared_sites.");

        // Get the values for later use
        List<String> accountings=getBusinessesForLinuxGroupAccount(conn, pkey);
        IntList aoServers=getAOServersForLinuxGroupAccount(conn, pkey);
        // Delete the group relations for this group
        conn.executeUpdate("delete from linux_group_accounts where pkey=?", pkey);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_GROUP_ACCOUNTS, accountings, aoServers, false);
    }

    public static void removeUnusedAlternateLinuxGroupAccount(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        String group,
        String username
    ) throws IOException, SQLException {
        int pkey=conn.executeIntQuery(
            "select\n"
            + "  coalesce(\n"
            + "    (\n"
            + "      select\n"
            + "        lga.pkey\n"
            + "      from\n"
            + "        linux_group_accounts lga\n"
            + "      where\n"
            + "        lga.group_name=?\n"
            + "        and lga.username=?\n"
            + "        and not lga.is_primary\n"
            + "        and (\n"
            + "          select\n"
            + "            htss.tomcat_site\n"
            + "          from\n"
            + "            linux_server_accounts lsa,\n"
            + "            httpd_shared_tomcats hst,\n"
            + "            httpd_tomcat_shared_sites htss,\n"
            + "            httpd_sites hs\n"
            + "          where\n"
            + "            lga.username=lsa.username\n"
            + "            and lsa.pkey=hst.linux_server_account\n"
            + "            and hst.pkey=htss.httpd_shared_tomcat\n"
            + "            and htss.tomcat_site=hs.pkey\n"
            + "            and hs.linux_group=lga.group_name\n"
            + "          limit 1\n"
            + "        ) is null\n"
            + "        and (\n"
            + "          select\n"
            + "            htss.tomcat_site\n"
            + "          from\n"
            + "            linux_server_groups lsg,\n"
            + "            httpd_shared_tomcats hst,\n"
            + "            httpd_tomcat_shared_sites htss,\n"
            + "            httpd_sites hs\n"
            + "          where\n"
            + "            lga.group_name=lsg.name\n"
            + "            and lsg.pkey=hst.linux_server_group\n"
            + "            and hst.pkey=htss.httpd_shared_tomcat\n"
            + "            and htss.tomcat_site=hs.pkey\n"
            + "            and hs.linux_account=lga.username\n"
            + "          limit 1\n"
            + "        ) is null\n"
            + "    ),\n"
            + "    -1\n"
            + "  )",
            group,
            username
        );
        if(pkey!=-1) {
            // Get the values for later use
            List<String> accountings=getBusinessesForLinuxGroupAccount(conn, pkey);
            IntList aoServers=getAOServersForLinuxGroupAccount(conn, pkey);
            conn.executeUpdate("delete from linux_group_accounts where pkey=?", pkey);

            // Notify all clients of the update
            invalidateList.addTable(conn, SchemaTable.TableID.LINUX_GROUP_ACCOUNTS, accountings, aoServers, false);
        }
    }

    public static void removeLinuxServerAccount(
        DatabaseConnection conn,
        RequestSource source, 
        InvalidateList invalidateList,
        int account
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "removeLinuxServerAccount", account);

        removeLinuxServerAccount(conn, invalidateList, account);
    }

    public static void removeLinuxServerAccount(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int account
    ) throws IOException, SQLException {
        String username=getUsernameForLinuxServerAccount(conn, account);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to remove LinuxServerAccount for user '"+LinuxAccount.MAIL+'\'');
        // The UID must be a user UID
        int uid=getUIDForLinuxServerAccount(conn, account);
        if(uid<UnixFile.MINIMUM_USER_UID) throw new SQLException("Not allowed to remove a system LinuxServerAccount: pkey="+account+", uid="+uid);

        // Must not contain a CVS repository
        String home=conn.executeStringQuery("select home from linux_server_accounts where pkey=?", account);
        int aoServer=getAOServerForLinuxServerAccount(conn, account);
        int count=conn.executeIntQuery(
            "select\n"
            + "  count(*)\n"
            + "from\n"
            + "  cvs_repositories cr\n"
            + "where\n"
            + "  linux_server_account=?\n"
            + "  and (\n"
            + "    path=?\n"
            + "    or substring(path from 1 for "+(home.length()+1)+")=?\n"
            + "  )",
            account,
            home,
            home+'/'
        );
        if(count>0) throw new SQLException("Home directory on "+aoServer+" contains "+count+" CVS "+(count==1?"repository":"repositories")+": "+home);

        // Delete the email configurations that depend on this account
        IntList addresses=conn.executeIntListQuery("select email_address from linux_acc_addresses where linux_server_account=?", account);
        int size=addresses.size();
        boolean addressesModified=size>0;
        for(int c=0;c<size;c++) {
            int address=addresses.getInt(c);
            conn.executeUpdate("delete from linux_acc_addresses where email_address=?", address);
            if(!EmailHandler.isEmailAddressUsed(conn, address)) {
                conn.executeUpdate("delete from email_addresses where pkey=?", address);
            }
        }

        String accounting=getBusinessForLinuxServerAccount(conn, account);

        // Delete the attachment blocks
        conn.executeUpdate("delete from email_attachment_blocks where linux_server_account=?", account);
        invalidateList.addTable(conn, SchemaTable.TableID.EMAIL_ATTACHMENT_BLOCKS, accounting, aoServer, false);

        // Delete the account from the server
        conn.executeUpdate("delete from linux_server_accounts where pkey=?", account);
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_SERVER_ACCOUNTS, accounting, aoServer, true);

        // Notify all clients of the update
        if(addressesModified) {
            invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACC_ADDRESSES, accounting, aoServer, false);
            invalidateList.addTable(conn, SchemaTable.TableID.EMAIL_ADDRESSES, accounting, aoServer, false);
        }
    }

    public static void removeLinuxServerGroup(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int group
    ) throws IOException, SQLException {
        checkAccessLinuxServerGroup(conn, source, "removeLinuxServerGroup", group);

        removeLinuxServerGroup(conn, invalidateList, group);
    }

    public static void removeLinuxServerGroup(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int group
    ) throws IOException, SQLException {
        String groupName=getGroupNameForLinuxServerGroup(conn, group);
        if(
            groupName.equals(LinuxGroup.FTPONLY)
            || groupName.equals(LinuxGroup.MAIL)
            || groupName.equals(LinuxGroup.MAILONLY)
        ) throw new SQLException("Not allowed to remove LinuxServerGroup for group '"+groupName+"'");

        // Get the server this group is on
        String accounting=getBusinessForLinuxServerGroup(conn, group);
        int aoServer=getAOServerForLinuxServerGroup(conn, group);
        // Must not be the primary group for any LinuxServerAccount on the same server
        int primaryCount=conn.executeIntQuery(
            "select\n"
            + "  count(*)\n"
            + "from\n"
            + "  linux_server_groups lsg,\n"
            + "  linux_group_accounts lga,\n"
            + "  linux_server_accounts lsa\n"
            + "where\n"
            + "  lsg.name=lga.group_name\n"
            + "  and lga.username=lsa.username\n"
            + "  and lsg.pkey=?\n"
            + "  and lga.is_primary\n"
            + "  and lsa.ao_server=?",
            group,
            aoServer
        );

        if(primaryCount>0) throw new SQLException("linux_server_group.pkey="+group+" is the primary group for "+primaryCount+" Linux server "+(primaryCount==1?"account":"accounts")+" on "+aoServer);
        // Delete from the database
        conn.executeUpdate("delete from linux_server_groups where pkey=?", group);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_SERVER_GROUPS, accounting, aoServer, true);
    }

    public static void setAutoresponder(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        int from,
        String subject,
        String content,
        boolean enabled
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "setAutoresponder", pkey);
        if(isLinuxServerAccountDisabled(conn, pkey)) throw new SQLException("Unable to set autoresponder, LinuxServerAccount disabled: "+pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set autoresponder for user '"+LinuxAccount.MAIL+'\'');
        String type=getTypeForLinuxAccount(conn, username);
        if(
            !type.equals(LinuxAccountType.EMAIL)
            && !type.equals(LinuxAccountType.USER)
        ) throw new SQLException("Not allowed to set autoresponder for this type of account: "+type);

        // The from must be on this account
        if(from!=-1) {
            int fromLSA=conn.executeIntQuery("select linux_server_account from linux_acc_addresses where pkey=?", from);
            if(fromLSA!=pkey) throw new SQLException("((linux_acc_address.pkey="+from+").linux_server_account="+fromLSA+")!=((linux_server_account.pkey="+pkey+").username="+username+")");
        }

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        int aoServer=getAOServerForLinuxServerAccount(conn, pkey);
        String path;
        if(content==null && !enabled) path=null;
        else {
            path=conn.executeStringQuery(
                "select coalesce(autoresponder_path, '') from linux_server_accounts where pkey=?",
                pkey
            );
            if(path.length()==0) {
                String home=conn.executeStringQuery("select home from linux_server_accounts where pkey=?", pkey);
                path=home+"/.autorespond.txt";
            }
        }
        int uid;
        int gid;
        if(!enabled) {
            uid=-1;
            gid=-1;
        } else {
            uid=getUIDForLinuxServerAccount(conn, pkey);
            gid=conn.executeIntQuery(
                "select\n"
                + "  lsg.gid\n"
                + "from\n"
                + "  linux_server_accounts lsa,\n"
                + "  linux_group_accounts lga,\n"
                + "  linux_server_groups lsg\n"
                + "where\n"
                + "  lsa.pkey=?\n"
                + "  and lsa.username=lga.username\n"
                + "  and lga.is_primary\n"
                + "  and lga.group_name=lsg.name\n"
                + "  and lsa.ao_server=lsg.ao_server",
                pkey
            );
        }
        PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false).prepareStatement(
            "update\n"
            + "  linux_server_accounts\n"
            + "set\n"
            + "  autoresponder_from=?,\n"
            + "  autoresponder_subject=?,\n"
            + "  autoresponder_path=?,\n"
            + "  is_autoresponder_enabled=?\n"
            + "where\n"
            + "  pkey=?"
        );
        try {
            if(from==-1) pstmt.setNull(1, Types.INTEGER);
            else pstmt.setInt(1, from);
            pstmt.setString(2, subject);
            pstmt.setString(3, path);
            pstmt.setBoolean(4, enabled);
            pstmt.setInt(5, pkey);
            pstmt.executeUpdate();
        } catch(SQLException err) {
            System.err.println("Error from update: "+pstmt.toString());
            throw err;
        } finally {
            pstmt.close();
        }

        // Store the content on the server
        if(path!=null) DaemonHandler.getDaemonConnector(
            conn,
            aoServer
        ).setAutoresponderContent(
            path,
            content==null?"":content,
            uid,
            gid
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_SERVER_ACCOUNTS, accounting, aoServer, false);
    }

    /**
     * Gets the contents of a user cron table.
     */
    public static void setCronTable(
        DatabaseConnection conn,
        RequestSource source,
        int lsa,
        String cronTable
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "setCronTable", lsa);
        if(isLinuxServerAccountDisabled(conn, lsa)) throw new SQLException("Unable to set cron table, LinuxServerAccount disabled: "+lsa);
        String username=getUsernameForLinuxServerAccount(conn, lsa);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the cron table for LinuxAccount named '"+LinuxAccount.MAIL+'\'');
        String type=getTypeForLinuxAccount(conn, username);
        if(
            !type.equals(LinuxAccountType.USER)
        ) throw new SQLException("Not allowed to set the cron table for LinuxAccounts of type '"+type+"', username="+username);
        int aoServer=getAOServerForLinuxServerAccount(conn, lsa);

        DaemonHandler.getDaemonConnector(conn, aoServer).setCronTable(username, cronTable);
    }

    public static void setLinuxAccountHomePhone(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String phone
    ) throws IOException, SQLException {
        checkAccessLinuxAccount(conn, source, "setLinuxAccountHomePhone", username);
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to set home phone number, LinuxAccount disabled: "+username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set home phone number for user '"+LinuxAccount.MAIL+'\'');
        String validity=LinuxAccount.checkGECOS(phone, "home phone");
        if(validity!=null) throw new SQLException(validity);

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        IntList aoServers=getAOServersForLinuxAccount(conn, username);

        conn.executeUpdate("update linux_accounts set home_phone=? where username=?", phone, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACCOUNTS, accounting, aoServers, false);
    }

    public static void setLinuxAccountName(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String name
    ) throws IOException, SQLException {
        String validity=LinuxAccount.checkGECOS(name, "full name");
        if(validity!=null) throw new SQLException(validity);

        checkAccessLinuxAccount(conn, source, "setLinuxAccountName", username);
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to set full name, LinuxAccount disabled: "+username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set LinuxAccountName for user '"+LinuxAccount.MAIL+'\'');

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        IntList aoServers=getAOServersForLinuxAccount(conn, username);

        conn.executeUpdate("update linux_accounts set name=? where username=?", name, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACCOUNTS, accounting, aoServers, false);
    }

    public static void setLinuxAccountOfficeLocation(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String location
    ) throws IOException, SQLException {
        checkAccessLinuxAccount(conn, source, "setLinuxAccountOfficeLocation", username);
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to set office location, LinuxAccount disabled: "+username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set office location for user '"+LinuxAccount.MAIL+'\'');
        String validity=LinuxAccount.checkGECOS(location, "location");
        if(validity!=null) throw new SQLException(validity);

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        IntList aoServers=getAOServersForLinuxAccount(conn, username);

        conn.executeUpdate("update linux_accounts set office_location=? where username=?", location, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACCOUNTS, accounting, aoServers, false);
    }

    public static void setLinuxAccountOfficePhone(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String phone
    ) throws IOException, SQLException {
        checkAccessLinuxAccount(conn, source, "setLinuxAccountOfficePhone", username);
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to set office phone number, LinuxAccount disabled: "+username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set office phone number for user '"+LinuxAccount.MAIL+'\'');
        String validity=LinuxAccount.checkGECOS(phone, "office phone");
        if(validity!=null) throw new SQLException(validity);

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        IntList aoServers=getAOServersForLinuxAccount(conn, username);

        conn.executeUpdate("update linux_accounts set office_phone=? where username=?", phone, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACCOUNTS, accounting, aoServers, false);
    }

    public static void setLinuxAccountShell(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String shell
    ) throws IOException, SQLException {
        checkAccessLinuxAccount(conn, source, "setLinuxAccountOfficeShell", username);
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to set shell, LinuxAccount disabled: "+username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set shell for account named '"+LinuxAccount.MAIL+'\'');
        String type=getTypeForLinuxAccount(conn, username);
        if(!LinuxAccountType.isAllowedShell(type, shell)) throw new SQLException("Shell '"+shell+"' not allowed for Linux accounts with the type '"+type+'\'');

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        IntList aoServers=getAOServersForLinuxAccount(conn, username);

        conn.executeUpdate("update linux_accounts set shell=? where username=?", shell, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.LINUX_ACCOUNTS, accounting, aoServers, false);
    }

    public static void setLinuxServerAccountPassword(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String password
    ) throws IOException, SQLException {
        try {
            BusinessHandler.checkPermission(conn, source, "setLinuxServerAccountPassword", AOServPermission.Permission.set_linux_server_account_password);
            checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountPassword", pkey);
            if(isLinuxServerAccountDisabled(conn, pkey)) throw new SQLException("Unable to set LinuxServerAccount password, account disabled: "+pkey);

            String username=getUsernameForLinuxServerAccount(conn, pkey);
            if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set password for LinuxServerAccount named '"+LinuxAccount.MAIL+"': "+pkey);
            String type=conn.executeStringQuery("select type from linux_accounts where username=?", username);

            // Make sure passwords can be set before doing a strength check
            if(!LinuxAccountType.canSetPassword(type)) throw new SQLException("Passwords may not be set for LinuxAccountType="+type);

            if(password!=null && password.length()>0) {
                // Perform the password check here, too.
                List<PasswordChecker.Result> results = LinuxAccount.checkPassword(UserId.valueOf(username), type, password);
                if(PasswordChecker.hasResults(results)) throw new SQLException("Invalid password: "+PasswordChecker.getResultsString(results).replace('\n', '|'));
            }

            String accounting=UsernameHandler.getBusinessForUsername(conn, username);
            int aoServer=getAOServerForLinuxServerAccount(conn, pkey);
            try {
                DaemonHandler.getDaemonConnector(conn, aoServer).setLinuxServerAccountPassword(username, password);
            } catch(IOException err) {
                System.err.println("Unable to set linux account password for "+username+" on "+aoServer);
                throw err;
            } catch(SQLException err) {
                System.err.println("Unable to set linux account password for "+username+" on "+aoServer);
                throw err;
            }

            // Update the ao_servers table for emailmon and ftpmon
            /*if(username.equals(LinuxAccount.EMAILMON)) {
                conn.executeUpdate("update ao_servers set emailmon_password=? where server=?", password==null||password.length()==0?null:password, aoServer);
                invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, ServerHandler.getBusinessesForServer(conn, aoServer), aoServer, false);
            } else if(username.equals(LinuxAccount.FTPMON)) {
                conn.executeUpdate("update ao_servers set ftpmon_password=? where server=?", password==null||password.length()==0?null:password, aoServer);
                invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, ServerHandler.getBusinessesForServer(conn, aoServer), aoServer, false);
            }*/
        } catch(ValidationException err) {
            throw new SQLException(err.getMessage(), err);
        }
    }

    public static void setLinuxServerAccountPredisablePassword(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int lsa,
        String password
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountPredisablePassword", lsa);
        if(password==null) {
            if(isLinuxServerAccountDisabled(conn, lsa)) throw new SQLException("Unable to clear LinuxServerAccount predisable password, account disabled: "+lsa);
        } else {
            if(!isLinuxServerAccountDisabled(conn, lsa)) throw new SQLException("Unable to set LinuxServerAccount predisable password, account not disabled: "+lsa);
        }

        // Update the database
        conn.executeUpdate(
            "update linux_server_accounts set predisable_password=? where pkey=?",
            password,
            lsa
        );

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, lsa),
            getAOServerForLinuxServerAccount(conn, lsa),
            false
        );
    }

    public static void setLinuxServerAccountJunkEmailRetention(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        int days
    ) throws IOException, SQLException {
        // Security checks
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountJunkEmailRetention", pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the junk email retention for LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        // Update the database
        if(days==-1) {
            conn.executeUpdate(
                "update linux_server_accounts set junk_email_retention=null where pkey=?",
                pkey
            );
        } else {
            conn.executeUpdate(
                "update linux_server_accounts set junk_email_retention=? where pkey=?",
                days,
                pkey
            );
        }

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    public static void setLinuxServerAccountSpamAssassinIntegrationMode(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String mode
    ) throws IOException, SQLException {
        // Security checks
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountSpamAssassinIntegrationMode", pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the spam assassin integration mode for LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        // Update the database
        conn.executeUpdate(
            "update linux_server_accounts set sa_integration_mode=? where pkey=?",
            mode,
            pkey
        );

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    public static void setLinuxServerAccountSpamAssassinRequiredScore(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        float required_score
    ) throws IOException, SQLException {
        // Security checks
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountSpamAssassinRequiredScore", pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the spam assassin required score for LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        // Update the database
        conn.executeUpdate(
            "update linux_server_accounts set sa_required_score=? where pkey=?",
            required_score,
            pkey
        );

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    public static void setLinuxServerAccountSpamAssassinDiscardScore(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        int discard_score
    ) throws IOException, SQLException {
        // Security checks
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountSpamAssassinDiscardScore", pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the spam assassin discard score for LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        // Update the database
        if(discard_score==-1) {
            conn.executeUpdate(
                "update linux_server_accounts set sa_discard_score=null where pkey=?",
                pkey
            );
        } else {
            conn.executeUpdate(
                "update linux_server_accounts set sa_discard_score=? where pkey=?",
                discard_score,
                pkey
            );
        }

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    public static void setLinuxServerAccountTrashEmailRetention(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        int days
    ) throws IOException, SQLException {
        // Security checks
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountTrashEmailRetention", pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the trash email retention for LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        // Update the database
        if(days==-1) {
            conn.executeUpdate(
                "update linux_server_accounts set trash_email_retention=null where pkey=?",
                pkey
            );
        } else {
            conn.executeUpdate(
                "update linux_server_accounts set trash_email_retention=? where pkey=?",
                days,
                pkey
            );
        }

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    public static void setLinuxServerAccountUseInbox(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        boolean useInbox
    ) throws IOException, SQLException {
        // Security checks
        checkAccessLinuxServerAccount(conn, source, "setLinuxServerAccountUseInbox", pkey);
        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set the use_inbox flag for LinuxAccount named '"+LinuxAccount.MAIL+'\'');

        // Update the database
        conn.executeUpdate(
            "update linux_server_accounts set use_inbox=? where pkey=?",
            useInbox,
            pkey
        );

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_SERVER_ACCOUNTS,
            getBusinessForLinuxServerAccount(conn, pkey),
            getAOServerForLinuxServerAccount(conn, pkey),
            false
        );
    }

    /**
     * Waits for any pending or processing account rebuild to complete.
     */
    public static void waitForLinuxAccountRebuild(
        DatabaseConnection conn,
        RequestSource source,
        int aoServer
    ) throws IOException, SQLException {
        ServerHandler.checkAccessServer(conn, source, "waitForLinuxAccountRebuild", aoServer);
        ServerHandler.waitForInvalidates(aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).waitForLinuxAccountRebuild();
    }

    static boolean canLinuxGroupAccessServer(DatabaseConnection conn, RequestSource source, String groupName, int aoServer) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            "select\n"
            + "  (\n"
            + "    select\n"
            + "      lg.name\n"
            + "    from\n"
            + "      linux_groups lg,\n"
            + "      packages pk,\n"
            + "      business_servers bs\n"
            + "    where\n"
            + "      lg.name=?\n"
            + "      and lg.package=pk.name\n"
            + "      and pk.accounting=bs.accounting\n"
            + "      and bs.server=?\n"
            + "    limit 1\n"
            + "  )\n"
            + "  is not null\n",
            groupName,
            aoServer
        );
    }

    static void checkLinuxGroupAccessServer(DatabaseConnection conn, RequestSource source, String action, String groupName, int server) throws IOException, SQLException {
        if(!canLinuxGroupAccessServer(conn, source, groupName, server)) {
            String message=
                "groupName="
                +groupName
                +" is not allowed to access server.pkey="
                +server
                +": action='"
                +action
                +"'"
            ;
            throw new SQLException(message);
        }
    }

    public static String getBusinessForLinuxGroup(DatabaseConnection conn, String name) throws IOException, SQLException {
        return conn.executeStringQuery("select pk.accounting from linux_groups lg, packages pk where lg.package=pk.name and lg.name=?", name);
    }

    public static List<String> getBusinessesForLinuxGroupAccount(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringListQuery(
           "select\n"
            + "  pk1.accounting\n"
            + "from\n"
            + "  linux_group_accounts lga1,\n"
            + "  linux_groups lg1,\n"
            + "  packages pk1\n"
            + "where\n"
            + "  lga1.pkey=?\n"
            + "  and lga1.group_name=lg1.name\n"
            + "  and lg1.package=pk1.name\n"
            + "union select\n"
            + "  pk2.accounting\n"
            + "from\n"
            + "  linux_group_accounts lga2,\n"
            + "  usernames un2,\n"
            + "  packages pk2\n"
            + "where\n"
            + "  lga2.pkey=?\n"
            + "  and lga2.username=un2.username\n"
            + "  and un2.package=pk2.name",
            pkey,
            pkey
        );
    }

    public static String getBusinessForLinuxServerAccount(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  pk.accounting\n"
            + "from\n"
            + "  linux_server_accounts lsa,\n"
            + "  usernames un,\n"
            + "  packages pk\n"
            + "where\n"
            + "  lsa.pkey=?\n"
            + "  and lsa.username=un.username\n"
            + "  and un.package=pk.name",
            pkey
        );
    }

    public static String getBusinessForLinuxServerGroup(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  pk.accounting\n"
            + "from\n"
            + "  linux_server_groups lsg,\n"
            + "  linux_groups lg,\n"
            + "  packages pk\n"
            + "where\n"
            + "  lsg.pkey=?\n"
            + "  and lsg.name=lg.name\n"
            + "  and lg.package=pk.name",
            pkey
        );
    }

    public static String getGroupNameForLinuxServerGroup(DatabaseConnection conn, int lsgPKey) throws IOException, SQLException {
        return conn.executeStringQuery("select name from linux_server_groups where pkey=?", lsgPKey);
    }

    public static int getAOServerForLinuxServerAccount(DatabaseConnection conn, int account) throws IOException, SQLException {
        return conn.executeIntQuery("select ao_server from linux_server_accounts where pkey=?", account);
    }

    public static int getAOServerForLinuxServerGroup(DatabaseConnection conn, int group) throws IOException, SQLException {
        return conn.executeIntQuery("select ao_server from linux_server_groups where pkey=?", group);
    }

    public static IntList getAOServersForLinuxAccount(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeIntListQuery("select ao_server from linux_server_accounts where username=?", username);
    }

    public static IntList getAOServersForLinuxGroup(DatabaseConnection conn, String name) throws IOException, SQLException {
        return conn.executeIntListQuery("select ao_server from linux_server_groups where name=?", name);
    }

    public static IntList getAOServersForLinuxGroupAccount(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntListQuery(
            "select\n"
            + "  lsg.ao_server\n"
            + "from\n"
            + "  linux_group_accounts lga,\n"
            + "  linux_server_groups lsg,\n"
            + "  linux_server_accounts lsa\n"
            + "where\n"
            + "  lga.pkey=?\n"
            + "  and lga.group_name=lsg.name\n"
            + "  and lga.username=lsa.username\n"
            + "  and lsg.ao_server=lsa.ao_server",
            pkey
        );
    }

    public static String getTypeForLinuxAccount(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeStringQuery("select type from linux_accounts where username=?", username);
    }

    public static String getTypeForLinuxServerAccount(DatabaseConnection conn, int account) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  la.type\n"
            + "from\n"
            + "  linux_server_accounts lsa,\n"
            + "  linux_accounts la\n"
            + "where\n"
            + "  lsa.pkey=?\n"
            + "  and lsa.username=la.username",
            account
        );
   }

    public static String getTypeForLinuxServerGroup(DatabaseConnection conn, int lsg) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  lg.type\n"
            + "from\n"
            + "  linux_server_groups lsg,\n"
            + "  linux_groups lg\n"
            + "where\n"
            + "  lsg.pkey=?\n"
            + "  and lsg.name=lg.name",
            lsg
        );
    }

    public static int getLinuxServerAccount(DatabaseConnection conn, String username, int aoServer) throws IOException, SQLException {
        int pkey=conn.executeIntQuery(
            "select coalesce(\n"
            + "  (\n"
            + "    select\n"
            + "      pkey\n"
            + "    from\n"
            + "      linux_server_accounts\n"
            + "    where\n"
            + "      username=?\n"
            + "      and ao_server=?\n"
            + "  ), -1\n"
            + ")",
            username,
            aoServer
        );
        if(pkey==-1) throw new SQLException("Unable to find LinuxServerAccount for "+username+" on "+aoServer);
        return pkey;
    }

    public static IntList getLinuxServerAccountsForLinuxAccount(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeIntListQuery("select pkey from linux_server_accounts where username=?", username);
    }

    public static IntList getLinuxServerGroupsForLinuxGroup(DatabaseConnection conn, String name) throws IOException, SQLException {
        return conn.executeIntListQuery("select pkey from linux_server_groups where name=?", name);
    }

    public static String getPackageForLinuxGroup(DatabaseConnection conn, String name) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  package\n"
            + "from\n"
            + "  linux_groups\n"
            + "where\n"
            + "  name=?",
            name
        );
    }

    public static String getPackageForLinuxServerGroup(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  lg.package\n"
            + "from\n"
            + "  linux_server_groups lsg,\n"
            + "  linux_groups lg\n"
            + "where\n"
            + "  lsg.pkey=?\n"
            + "  and lsg.name=lg.name",
            pkey
        );
    }

    public static int getGIDForLinuxServerGroup(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select gid from linux_server_groups where pkey=?", pkey);
    }

    public static int getUIDForLinuxServerAccount(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select uid from linux_server_accounts where pkey=?", pkey);
    }

    public static String getLinuxAccountForLinuxGroupAccount(DatabaseConnection conn, int lga) throws IOException, SQLException {
        return conn.executeStringQuery("select username from linux_group_accounts where pkey=?", lga);
    }

    public static String getLinuxGroupForLinuxGroupAccount(DatabaseConnection conn, int lga) throws IOException, SQLException {
        return conn.executeStringQuery("select group_name from linux_group_accounts where pkey=?", lga);
    }

    public static String getUsernameForLinuxServerAccount(DatabaseConnection conn, int account) throws IOException, SQLException {
        return conn.executeStringQuery("select username from linux_server_accounts where pkey=?", account);
    }

    public static boolean comparePassword(
        DatabaseConnection conn,
        RequestSource source, 
        int pkey, 
        String password
    ) throws IOException, SQLException {
        checkAccessLinuxServerAccount(conn, source, "comparePassword", pkey);
        if(isLinuxServerAccountDisabled(conn, pkey)) throw new SQLException("Unable to compare password, LinuxServerAccount disabled: "+pkey);

        String username=getUsernameForLinuxServerAccount(conn, pkey);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to compare password for LinuxServerAccount named '"+LinuxAccount.MAIL+"': "+pkey);
        String type=conn.executeStringQuery("select type from linux_accounts where username=?", username);

        // Make sure passwords can be set before doing a comparison
        if(!LinuxAccountType.canSetPassword(type)) throw new SQLException("Passwords may not be compared for LinuxAccountType="+type);

        // Perform the password comparison
        return DaemonHandler.getDaemonConnector(
            conn,
            getAOServerForLinuxServerAccount(conn, pkey)
        ).compareLinuxAccountPassword(username, password);
    }

    public static void setPrimaryLinuxGroupAccount(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        checkAccessLinuxGroupAccount(conn, source, "setPrimaryLinuxGroupAccount", pkey);
        String username=conn.executeStringQuery("select username from linux_group_accounts where pkey=?", pkey);
        if(isLinuxAccountDisabled(conn, username)) throw new SQLException("Unable to set primary LinuxGroupAccount, LinuxAccount disabled: "+username);
        String group=conn.executeStringQuery("select group_name from linux_group_accounts where pkey=?", pkey);

        conn.executeUpdate(
            "update linux_group_accounts set is_primary=true where pkey=?",
            pkey
        );
        conn.executeUpdate(
            "update linux_group_accounts set is_primary=false where is_primary and pkey!=? and username=?",
            pkey,
            username
        );
        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.LINUX_GROUP_ACCOUNTS,
            InvalidateList.getCollection(UsernameHandler.getBusinessForUsername(conn, username), getBusinessForLinuxGroup(conn, group)),
            getAOServersForLinuxGroupAccount(conn, pkey),
            false
        );
    }
}