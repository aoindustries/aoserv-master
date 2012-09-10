/*
 * Copyright 2001-2012 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master;

import com.aoindustries.aoserv.client.AOServPermission;
import com.aoindustries.aoserv.client.Business;
import com.aoindustries.aoserv.client.BusinessAdministrator;
import com.aoindustries.aoserv.client.CountryCode;
import com.aoindustries.aoserv.client.EmailAddress;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.MasterUser;
import com.aoindustries.aoserv.client.NoticeLog;
import com.aoindustries.aoserv.client.PasswordChecker;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.client.Username;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.sql.WrappedSQLException;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import com.aoindustries.util.StringUtility;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The <code>BusinessHandler</code> handles all the accesses to the Business tables.
 *
 * @author  AO Industries, Inc.
 */
final public class BusinessHandler {

    private BusinessHandler() {
    }

    private static final Object businessAdministratorsLock=new Object();
    private static Map<String,BusinessAdministrator> businessAdministrators;

    private static final Object usernameBusinessesLock=new Object();
    private static Map<String,List<String>> usernameBusinesses;
    private final static Map<String,Boolean> disabledBusinessAdministrators=new HashMap<String,Boolean>();
    private final static Map<String,Boolean> disabledBusinesses=new HashMap<String,Boolean>();

    public static boolean canAccessBusiness(DatabaseConnection conn, RequestSource source, String accounting) throws IOException, SQLException {
        //String username=source.getUsername();
        return
            getAllowedBusinesses(conn, source)
            .contains(
                accounting //UsernameHandler.getBusinessForUsername(conn, username)
            )
        ;
    }
    
    public static boolean canAccessDisableLog(DatabaseConnection conn, RequestSource source, int pkey, boolean enabling) throws IOException, SQLException {
        String username=source.getUsername();
        String disabledBy=getDisableLogDisabledBy(conn, pkey);
        if(enabling) {
            String baAccounting=UsernameHandler.getBusinessForUsername(conn, username);
            String dlAccounting=UsernameHandler.getBusinessForUsername(conn, disabledBy);
            return isBusinessOrParent(conn, baAccounting, dlAccounting);
        } else {
            return username.equals(disabledBy);
        }
    }

    public static void cancelBusiness(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        String cancelReason
    ) throws IOException, SQLException {
        // Check permissions
        checkPermission(conn, source, "cancelBusiness", AOServPermission.Permission.cancel_business);

        // Check access to business
        checkAccessBusiness(conn, source, "cancelBusiness", accounting);

        if(accounting.equals(getRootBusiness())) throw new SQLException("Not allowed to cancel the root business: "+accounting);

        // Business must be disabled
        if(!isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to cancel Business, Business not disabled: "+accounting);

        // Business must not already be canceled
        if(isBusinessCanceled(conn, accounting)) throw new SQLException("Unable to cancel Business, Business already canceled: "+accounting);

        // Update the database
        conn.executeUpdate(
            "update businesses set canceled=now(), cancel_reason=? where accounting=?",
            cancelReason,
            accounting
        );

        // Notify the clients
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, getServersForBusiness(conn, accounting), false);
    }

    public static boolean canBusinessServer(
        DatabaseConnection conn,
        RequestSource source,
        int server,
        String column
    ) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            Connection.TRANSACTION_READ_COMMITTED,
            true,
            true,
            "select\n"
            + "  bs."+column+"\n"
            + "from\n"
            + "  usernames un,\n"
            + "  packages pk,\n"
            + "  business_servers bs\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and un.package=pk.name\n"
            + "  and pk.accounting=bs.accounting\n"
            + "  and bs.server=?",
            source.getUsername(),
            server
        );
    }

    public static void checkAccessBusiness(DatabaseConnection conn, RequestSource source, String action, String accounting) throws IOException, SQLException {
        if(!canAccessBusiness(conn, source, accounting)) {
            String message=
            "business_administrator.username="
            +source.getUsername()
            +" is not allowed to access business: action='"
            +action
            +"', accounting="
            +accounting
            ;
            throw new SQLException(message);
        }
    }
    
    public static void checkAccessDisableLog(DatabaseConnection conn, RequestSource source, String action, int pkey, boolean enabling) throws IOException, SQLException {
        if(!canAccessDisableLog(conn, source, pkey, enabling)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" is not allowed to access disable_log: action='"
                +action
                +"', pkey="
                +pkey
            ;
            throw new SQLException(message);
        }
    }

    public static void checkAddBusiness(DatabaseConnection conn, RequestSource source, String action, String parent, int server) throws IOException, SQLException {
        boolean canAdd = conn.executeBooleanQuery("select can_add_businesses from businesses where accounting=?", UsernameHandler.getBusinessForUsername(conn, source.getUsername()));
        if(canAdd) {
            MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
            if(mu!=null) {
                if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) canAdd = false;
            } else {
                canAdd =
                    canAccessBusiness(conn, source, parent)
                    && ServerHandler.canAccessServer(conn, source, server)
                ;
            }
        }
        if(!canAdd) {
            String message=
            "business_administrator.username="
            +source.getUsername()
            +" is not allowed to add business: action='"
            +action
            +"', parent="
            +parent
            +", server="
            +server
            ;
            throw new SQLException(message);
        }
    }

    private static Map<String,Set<String>> cachedPermissions;
    private static final Object cachedPermissionsLock = new Object();

    public static boolean hasPermission(DatabaseConnection conn, RequestSource source, AOServPermission.Permission permission) throws IOException, SQLException {
        synchronized(cachedPermissionsLock) {
            if(cachedPermissions==null) {
        Statement stmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).createStatement();
        try {
        Map<String,Set<String>> newCache = new HashMap<String,Set<String>>();
        ResultSet results=stmt.executeQuery("select username, permission from business_administrator_permissions");
        while(results.next()) {
                        String username = results.getString(1);
                        Set<String> permissions = newCache.get(username);
                        if(permissions==null) newCache.put(username, permissions = new HashSet<String>());
                        permissions.add(results.getString(2));
        }
        cachedPermissions = newCache;
        } finally {
        stmt.close();
        }
            }
            Set<String> permissions = cachedPermissions.get(source.getUsername());
            return permissions!=null && permissions.contains(permission.name());
        }
    }

    public static void checkPermission(DatabaseConnection conn, RequestSource source, String action, AOServPermission.Permission permission) throws IOException, SQLException {
        if(!hasPermission(conn, source, permission)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" does not have the \""+permission.name()+"\" permission.  Not allowed to make the following call: "
                +action
            ;
            throw new SQLException(message);
        }
    }

    public static List<String> getAllowedBusinesses(DatabaseConnection conn, RequestSource source) throws IOException, SQLException {
	    synchronized(usernameBusinessesLock) {
            String username=source.getUsername();
            if(usernameBusinesses==null) usernameBusinesses=new HashMap<String,List<String>>();
            List<String> SV=usernameBusinesses.get(username);
            if(SV==null) {
                List<String> V;
                        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
                        if(mu!=null) {
                            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                                V=conn.executeStringListQuery(
                                    "select distinct\n"
                                    + "  bu.accounting\n"
                                    + "from\n"
                                    + "  master_servers ms,\n"
                                    + "  business_servers bs,\n"
                                    + "  businesses bu\n"
                                    + "where\n"
                                    + "  ms.username=?\n"
                                    + "  and ms.server=bs.server\n"
                                    + "  and bs.accounting=bu.accounting",
                                    username
                                );
                            } else {
                                V=conn.executeStringListQuery("select accounting from businesses");
                            }
                        } else {
                            V=conn.executeStringListQuery(
                                "select\n"
                                + "  bu1.accounting\n"
                                + "from\n"
                                + "  usernames un,\n"
                                + "  packages pk,\n"
                                + TableHandler.BU1_PARENTS_JOIN_NO_COMMA
                                + "where\n"
                                + "  un.username=?\n"
                                + "  and un.package=pk.name\n"
                                + "  and (\n"
                                + TableHandler.PK_BU1_PARENTS_WHERE
                                + "  )",
                                username
                            );
                        }

                int size=V.size();
                SV=new SortedArrayList<String>();
                for(int c=0;c<size;c++) SV.add(V.get(c));
                usernameBusinesses.put(username, SV);
            }
            return SV;
	    }
    }
    
    public static String getBusinessForDisableLog(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select accounting from disable_log where pkey=?", pkey);
    }

    /**
     * Creates a new <code>Business</code>.
     */
    public static void addBusiness(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        String contractVersion,
        int defaultServer,
        String parent,
        boolean can_add_backup_servers,
        boolean can_add_businesses,
        boolean can_see_prices,
        boolean billParent
    ) throws IOException, SQLException {
        if(!Business.isValidAccounting(accounting)) throw new SQLException("Invalid accounting code: "+accounting);

        checkAddBusiness(conn, source, "addBusiness", parent, defaultServer);

        if(isBusinessDisabled(conn, parent)) throw new SQLException("Unable to add Business '"+accounting+"', parent is disabled: "+parent);

        // Must not exceed the maximum business tree depth
        int newDepth=getDepthInBusinessTree(conn, parent)+1;
        if(newDepth>Business.MAXIMUM_BUSINESS_TREE_DEPTH) throw new SQLException("Unable to add Business '"+accounting+"', the maximum depth of the business tree ("+Business.MAXIMUM_BUSINESS_TREE_DEPTH+") would be exceeded.");

        conn.executeUpdate(
            "insert into businesses (\n"
            + "  accounting,\n"
            + "  contract_version,\n"
            + "  parent,\n"
            + "  can_add_backup_server,\n"
            + "  can_add_businesses,\n"
            + "  can_see_prices,\n"
            + "  auto_enable,\n"
            + "  bill_parent\n"
            + ") values(\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  true,\n"
            + "  ?\n"
            + ")",
            accounting,
            contractVersion,
            parent,
            can_add_backup_servers,
            can_add_businesses,
            can_see_prices,
            billParent
        );
        conn.executeUpdate(
            "insert into business_servers(\n"
            + "  accounting,\n"
            + "  server,\n"
            + "  is_default,\n"
            + "  can_control_apache,\n"
            + "  can_control_cron,\n"
            + "  can_control_mysql,\n"
            + "  can_control_postgresql,\n"
            + "  can_control_xfs,\n"
            + "  can_control_xvfb,\n"
            + "  can_vnc_console,\n"
            + "  can_control_virtual_server\n"
            + ") values(\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  true,\n"
            + "  false,\n"
            + "  false,\n"
            + "  false,\n"
            + "  false,\n"
            + "  false,\n"
            + "  false,\n"
            + "  false,\n"
            + "  false\n"
            + ")",
            accounting,
            defaultServer
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, defaultServer, false);
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, accounting, defaultServer, false);
    }
    
    /**
     * Creates a new <code>BusinessAdministrator</code>.
     */
    public static void addBusinessAdministrator(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String name,
        String title,
        long birthday,
        boolean isPrivate,
        String workPhone,
        String homePhone,
        String cellPhone,
        String fax,
        String email,
        String address1,
        String address2,
        String city,
        String state,
        String country,
        String zip,
        boolean enableEmailSupport
    ) throws IOException, SQLException {
        UsernameHandler.checkAccessUsername(conn, source, "addBusinessAdministrator", username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to add BusinessAdministrator named mail");
        String check = Username.checkUsername(username);
        if(check!=null) throw new SQLException(check);
        if (country!=null && country.equals(CountryCode.US)) state=convertUSState(conn, state);

        String supportCode = enableEmailSupport ? generateSupportCode(conn) : null;
        PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false).prepareStatement(
            "insert into business_administrators values(?,?,?,?,?,false,?,now(),?,?,?,?,?,?,?,?,?,?,?,null,true,?)"
        );
        try {
            pstmt.setString(1, username);
            pstmt.setString(2, BusinessAdministrator.NO_PASSWORD);
            pstmt.setString(3, name);
            pstmt.setString(4, title);
            if(birthday==-1) pstmt.setNull(5, Types.TIMESTAMP);
            else pstmt.setTimestamp(5, new Timestamp(birthday));
            pstmt.setBoolean(6, isPrivate);
            pstmt.setString(7, workPhone);
            pstmt.setString(8, homePhone);
            pstmt.setString(9, cellPhone);
            pstmt.setString(10, fax);
            pstmt.setString(11, email);
            pstmt.setString(12, address1);
            pstmt.setString(13, address2);
            pstmt.setString(14, city);
            pstmt.setString(15, state);
            pstmt.setString(16, country);
            pstmt.setString(17, zip);
            pstmt.setString(18, supportCode);
            pstmt.executeUpdate();
        } catch(SQLException err) {
            System.err.println("Error from query: "+pstmt.toString());
            throw err;
        } finally {
            pstmt.close();
        }

        // administrators default to having the same permissions as the person who created them
        conn.executeUpdate(
            "insert into business_administrator_permissions (username, permission) select ?, permission from business_administrator_permissions where username=?",
            username,
            source.getUsername()
        );

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATOR_PERMISSIONS, accounting, InvalidateList.allServers, false);
    }
    
    public static String convertUSState(DatabaseConnection conn, String state) throws IOException, SQLException {
        String newState = conn.executeStringQuery(
            Connection.TRANSACTION_READ_COMMITTED,
            true,
            true,
            "select coalesce((select code from us_states where upper(name)=upper(?) or code=upper(?)),'')",
            state,
            state
        );
        if(newState.length()==0) {
            throw new SQLException(
                state==null || state.length()==0
                ?"State required for the United States"
                :"Invalid US state: "+state
            );
        }
        return newState;
    }
    
    /**
     * Creates a new <code>BusinessProfile</code>.
     */
    public static int addBusinessProfile(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        String name,
        boolean isPrivate,
        String phone,
        String fax,
        String address1,
        String address2,
        String city,
        String state,
        String country,
        String zip,
        boolean sendInvoice,
        String billingContact,
        String billingEmail,
        String technicalContact,
        String technicalEmail
    ) throws IOException, SQLException {
        checkAccessBusiness(conn, source, "createBusinessProfile", accounting);

        if (country.equals(CountryCode.US)) state=convertUSState(conn, state);

        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('business_profiles_pkey_seq')");
        int priority=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select coalesce(max(priority)+1, 1) from business_profiles where accounting=?", accounting);

        PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false).prepareStatement("insert into business_profiles values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)");
        try {
            pstmt.setInt(1, pkey);
            pstmt.setString(2, accounting);
            pstmt.setInt(3, priority);
            pstmt.setString(4, name);
            pstmt.setBoolean(5, isPrivate);
            pstmt.setString(6, phone);
            pstmt.setString(7, fax);
            pstmt.setString(8, address1);
            pstmt.setString(9, address2);
            pstmt.setString(10, city);
            pstmt.setString(11, state);
            pstmt.setString(12, country);
            pstmt.setString(13, zip);
            pstmt.setBoolean(14, sendInvoice);
            pstmt.setTimestamp(15, new Timestamp(System.currentTimeMillis()));
            pstmt.setString(16, billingContact);
            pstmt.setString(17, billingEmail);
            pstmt.setString(18, technicalContact);
            pstmt.setString(19, technicalEmail);
            pstmt.executeUpdate();
        } finally {
            pstmt.close();
        }
        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_PROFILES, accounting, InvalidateList.allServers, false);
        return pkey;
    }
    
    /**
     * Creates a new <code>BusinessServer</code>.
     */
    public static int addBusinessServer(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        int server
    ) throws IOException, SQLException {
        // Must be allowed to access the Business
        checkAccessBusiness(conn, source, "addBusinessServer", accounting);
        if(!accounting.equals(getRootBusiness())) ServerHandler.checkAccessServer(conn, source, "addBusinessServer", server);

        return addBusinessServer(conn, invalidateList, accounting, server);
    }
    
    /**
     * Creates a new <code>BusinessServer</code>.
     */
    public static int addBusinessServer(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        String accounting,
        int server
    ) throws IOException, SQLException {
        if(isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to add BusinessServer, Business disabled: "+accounting);

        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('business_servers_pkey_seq')");

        // Parent business must also have access to the server
        if(
            !accounting.equals(getRootBusiness())
            && conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      bs.pkey\n"
                + "    from\n"
                + "      businesses bu,\n"
                + "      business_servers bs\n"
                + "    where\n"
                + "      bu.accounting=?\n"
                + "      and bu.parent=bs.accounting\n"
                + "      and bs.server=?\n"
                + "  ) is null",
                accounting,
                server
            )
        ) throw new SQLException("Unable to add business_server, parent does not have access to server.  accounting="+accounting+", server="+server);

        boolean hasDefault=conn.executeBooleanQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select (select pkey from business_servers where accounting=? and is_default limit 1) is not null", accounting);

        conn.executeUpdate(
            "insert into business_servers values(?,?,?,?,false,false,false,false,false,false,false)",
            pkey,
            accounting,
            server,
            !hasDefault
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, accounting, server, false);
        invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, accounting, server, false);
        invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, accounting, server, false);
        return pkey;
    }

    /**
     * Creates a new <code>DistroLog</code>.
     */
    public static int addDisableLog(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        String disableReason
    ) throws IOException, SQLException {
        checkAccessBusiness(conn, source, "addDisableLog", accounting);

        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('disable_log_pkey_seq')");
        String username=source.getUsername();
        conn.executeUpdate(
            "insert into disable_log values(?,now(),?,?,?)",
            pkey,
            accounting,
            username,
            disableReason
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.DISABLE_LOG,
            accounting,
            InvalidateList.allServers,
            false
        );
        return pkey;
    }

    /**
     * Adds a notice log.
     */
    public static void addNoticeLog(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        String billingContact,
        String emailAddress,
        int balance,
        String type,
        int transid
    ) throws IOException, SQLException {
        checkAccessBusiness(conn, source, "addNoticeLog", accounting);
        if(transid!=NoticeLog.NO_TRANSACTION) TransactionHandler.checkAccessTransaction(conn, source, "addNoticeLog", transid);

        PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false).prepareStatement(
            "insert into\n"
            + "  notice_log\n"
            + "(\n"
            + "  accounting,\n"
            + "  billing_contact,\n"
            + "  billing_email,\n"
            + "  balance,\n"
            + "  notice_type,\n"
            + "  transid\n"
            + ") values(\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?::decimal(9,2),\n"
            + "  ?,\n"
            + "  ?\n"
            + ")"
        );
        try {
            pstmt.setString(1, accounting);
            pstmt.setString(2, billingContact);
            pstmt.setString(3, emailAddress);
            pstmt.setString(4, SQLUtility.getDecimal(balance));
            pstmt.setString(5, type);
            if(transid==NoticeLog.NO_TRANSACTION) pstmt.setNull(6, Types.INTEGER);
            else pstmt.setInt(6, transid);
            pstmt.executeUpdate();
        } finally {
            pstmt.close();
        }

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.NOTICE_LOG, accounting, InvalidateList.allServers, false);
    }
    
    public static void disableBusiness(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int disableLog,
        String accounting
    ) throws IOException, SQLException {
        if(isBusinessDisabled(conn, accounting)) throw new SQLException("Business is already disabled: "+accounting);
        if(accounting.equals(getRootBusiness())) throw new SQLException("Not allowed to disable the root business: "+accounting);
        checkAccessDisableLog(conn, source, "disableBusiness", disableLog, false);
        checkAccessBusiness(conn, source, "disableBusiness", accounting);
        List<String> packages=getPackagesForBusiness(conn, accounting);
        for(int c=0;c<packages.size();c++) {
            String packageName=packages.get(c);
            if(!PackageHandler.isPackageDisabled(conn, packageName)) {
                throw new SQLException("Cannot disable Business '"+accounting+"': Package not disabled: "+packageName);
            }
        }

        conn.executeUpdate(
            "update businesses set disable_log=? where accounting=?",
            disableLog,
            accounting
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, getServersForBusiness(conn, accounting), false);
    }

    public static void disableBusinessAdministrator(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int disableLog,
        String username
    ) throws IOException, SQLException {
        if(isBusinessAdministratorDisabled(conn, username)) throw new SQLException("BusinessAdministrator is already disabled: "+username);
        checkAccessDisableLog(conn, source, "disableBusinessAdministrator", disableLog, false);
        UsernameHandler.checkAccessUsername(conn, source, "disableBusinessAdministrator", username);

        conn.executeUpdate(
            "update business_administrators set disable_log=? where username=?",
            disableLog,
            username
        );

        // Notify all clients of the update
        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, getServersForBusiness(conn, accounting), false);
    }

    public static void enableBusiness(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting
    ) throws IOException, SQLException {
        checkAccessBusiness(conn, source, "enableBusiness", accounting);

        int disableLog=getDisableLogForBusiness(conn, accounting);
        if(disableLog==-1) throw new SQLException("Business is already enabled: "+accounting);
        checkAccessDisableLog(conn, source, "enableBusiness", disableLog, true);

        if(isBusinessCanceled(conn, accounting)) throw new SQLException("Unable to enable Business, Business canceled: "+accounting);

        conn.executeUpdate(
            "update businesses set disable_log=null where accounting=?",
            accounting
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, getServersForBusiness(conn, accounting), false);
    }

    public static void enableBusinessAdministrator(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username
    ) throws IOException, SQLException {
        int disableLog=getDisableLogForBusinessAdministrator(conn, username);
        if(disableLog==-1) throw new SQLException("BusinessAdministrator is already enabled: "+username);
        checkAccessDisableLog(conn, source, "enableBusinessAdministrator", disableLog, true);
        UsernameHandler.checkAccessUsername(conn, source, "enableBusinessAdministrator", username);

        conn.executeUpdate(
            "update business_administrators set disable_log=null where username=?",
            username
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.BUSINESS_ADMINISTRATORS,
            UsernameHandler.getBusinessForUsername(conn, username),
            UsernameHandler.getServersForUsername(conn, username),
            false
        );
    }

    /**
     * Generates a random, unused support code.
     */
    public static String generateSupportCode(
        DatabaseConnection conn
    ) throws IOException, SQLException {
        Random random = MasterServer.getRandom();
        StringBuilder SB = new StringBuilder(11);
        for(int range=1000000; range<1000000000; range *= 10) {
            for(int attempt=0; attempt<1000; attempt++) {
                SB.setLength(0);
                SB.append((char)('a'+random.nextInt('z'+1-'a')));
                SB.append((char)('a'+random.nextInt('z'+1-'a')));
                SB.append(random.nextInt(range));
                String supportCode = SB.toString();
                if(conn.executeBooleanQuery("select (select support_code from business_administrators where support_code=?) is null", supportCode)) return supportCode;
            }
        }
        throw new SQLException("Failed to generate support code after thousands of attempts");
    }

    public static String generateAccountingCode(
        DatabaseConnection conn,
        String template
    ) throws IOException, SQLException {
        // Load the entire list of accounting codes
        List<String> codes=conn.executeStringListQuery(Connection.TRANSACTION_READ_COMMITTED, true, "select accounting from businesses");
        int size=codes.size();

        // Sort them
        List<String> sorted=new SortedArrayList<String>(size);
        for(int c=0;c<size;c++) sorted.add(codes.get(c));

        // Find one that is not used
        String goodOne=null;
        for(int c=1;c<Integer.MAX_VALUE;c++) {
            String accounting=template+c;
            if(!Business.isValidAccounting(accounting)) throw new SQLException("Invalid accounting code: "+accounting);
            if(!sorted.contains(accounting)) {
                goodOne=accounting;
                break;
            }
        }

        // If could not find one, report and error
        if(goodOne==null) throw new SQLException("Unable to find available accounting code for template: "+template);

        // Write the one we found
        return goodOne;
    }

    /**
     * Gets the depth of the business in the business tree.  root_accounting is at depth 1.
     * 
     * @return  the depth between 1 and Business.MAXIMUM_BUSINESS_TREE_DEPTH, inclusive.
     */
    public static int getDepthInBusinessTree(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        int depth=0;
        while(accounting!=null) {
            String parent=conn.executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select parent from businesses where accounting=?", accounting);
            depth++;
            accounting=parent;
        }
        if(depth<1 || depth>Business.MAXIMUM_BUSINESS_TREE_DEPTH) throw new SQLException("Unexpected depth: "+depth);
        return depth;
    }

    public static String getDisableLogDisabledBy(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select disabled_by from disable_log where pkey=?", pkey);
    }

    public static int getDisableLogForBusiness(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select coalesce(disable_log, -1) from businesses where accounting=?", accounting);
    }

    final private static Map<String,Integer> businessAdministratorDisableLogs=new HashMap<String,Integer>();
    public static int getDisableLogForBusinessAdministrator(DatabaseConnection conn, String username) throws IOException, SQLException {
        synchronized(businessAdministratorDisableLogs) {
            if(businessAdministratorDisableLogs.containsKey(username)) return businessAdministratorDisableLogs.get(username).intValue();
            int disableLog=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select coalesce(disable_log, -1) from business_administrators where username=?", username);
            businessAdministratorDisableLogs.put(username, Integer.valueOf(disableLog));
            return disableLog;
        }
    }

    public static List<String> getPackagesForBusiness(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeStringListQuery(Connection.TRANSACTION_READ_COMMITTED, true, "select name from packages where accounting=?", accounting);
    }

    public static IntList getServersForBusiness(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeIntListQuery(Connection.TRANSACTION_READ_COMMITTED, true, "select server from business_servers where accounting=?", accounting);
    }

    public static String getRootBusiness() throws IOException {
        return MasterConfiguration.getRootBusiness();
    }

    public static boolean isAccountingAvailable(
        DatabaseConnection conn,
        String accounting
    ) throws IOException, SQLException {
        return conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select count(*) from businesses where accounting=?", accounting)==0;
    }

    public static boolean isBusinessAdministratorPasswordSet(
        DatabaseConnection conn,
        RequestSource source,
        String username
    ) throws IOException, SQLException {
        UsernameHandler.checkAccessUsername(conn, source, "isBusinessAdministratorPasswordSet", username);
        return !BusinessAdministrator.NO_PASSWORD.equals(
            conn
                .executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select password from business_administrators where username=?", username)
                .trim()
        );
    }

    public static void removeBusinessAdministrator(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username
    ) throws IOException, SQLException {
        if(username.equals(source.getUsername())) throw new SQLException("Not allowed to remove self: "+username);
        UsernameHandler.checkAccessUsername(conn, source, "removeBusinessAdministrator", username);

        removeBusinessAdministrator(conn, invalidateList, username);
    }

    public static void removeBusinessAdministrator(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        String username
    ) throws IOException, SQLException {
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to remove Username named '"+LinuxAccount.MAIL+'\'');

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);

        conn.executeUpdate("delete from business_administrator_permissions where username=?", username);
        conn.executeUpdate("delete from business_administrators where username=?", username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
    }

    /**
     * Removes a <code>BusinessServer</code>.
     */
    public static void removeBusinessServer(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        String accounting=conn.executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select accounting from business_servers where pkey=?", pkey);
        int server=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select server from business_servers where pkey=?", pkey);

        // Must be allowed to access this Business
        checkAccessBusiness(conn, source, "removeBusinessServer", accounting);

        // Do not remove the default unless it is the only one left
        if(
            conn.executeBooleanQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select is_default from business_servers where pkey=?", pkey)
            && conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select count(*) from business_servers where accounting=?", accounting)>1
        ) {
            throw new SQLException("Cannot remove the default business_server unless it is the last business_server for a business: "+pkey);
        }

        removeBusinessServer(
            conn,
            invalidateList,
            pkey
        );
    }

    /**
     * Removes a <code>BusinessServer</code>.
     */
    public static void removeBusinessServer(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        String accounting=conn.executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select accounting from business_servers where pkey=?", pkey);
        int server=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select server from business_servers where pkey=?", pkey);

        // No children should be able to access the server
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      bs.pkey\n"
                + "    from\n"
                + "      businesses bu,\n"
                + "      business_servers bs\n"
                + "    where\n"
                + "      bu.parent=?\n"
                + "      and bu.accounting=bs.accounting\n"
                + "      and bs.server=?\n"
                + "    limit 1\n"
                + "  ) is not null",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still has at least one child Business able to access Server="+server);

        /*
         * Business must not have any resources on the server
         */
        // email_pipes
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      ep.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      email_pipes ep\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=ep.package\n"
                + "      and ep.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one EmailPipe on Server="+server);

        // httpd_sites
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      hs.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      httpd_sites hs\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=hs.package\n"
                + "      and hs.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one HttpdSite on Server="+server);

        // ip_addresses
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      ia.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      ip_addresses ia,\n"
                + "      net_devices nd\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=ia.package\n"
                + "      and ia.net_device=nd.pkey\n"
                + "      and nd.server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one IPAddress on Server="+server);

        // linux_server_accounts
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      lsa.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      usernames un,\n"
                + "      linux_server_accounts lsa\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=un.package\n"
                + "      and un.username=lsa.username\n"
                + "      and lsa.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one LinuxServerAccount on Server="+server);

        // linux_server_groups
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      lsg.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      linux_groups lg,\n"
                + "      linux_server_groups lsg\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=lg.package\n"
                + "      and lg.name=lsg.name\n"
                + "      and lsg.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one LinuxServerGroup on Server="+server);

        // mysql_databases
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      md.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      mysql_databases md,\n"
                + "      mysql_servers ms\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=md.package\n"
                + "      and md.mysql_server=ms.pkey\n"
                + "      and ms.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one MySQLDatabase on Server="+server);

        // mysql_server_users
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      msu.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      usernames un,\n"
                + "      mysql_server_users msu,\n"
                + "      mysql_servers ms\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=un.package\n"
                + "      and un.username=msu.username\n"
                + "      and msu.mysql_server=ms.pkey\n"
                + "      and ms.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one MySQLServerUser on Server="+server);

        // net_binds
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      nb.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      net_binds nb\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=nb.package\n"
                + "      and nb.server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one NetBind on Server="+server);

        // postgres_databases
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      pd.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      usernames un,\n"
                + "      postgres_servers ps,\n"
                + "      postgres_server_users psu,\n"
                + "      postgres_databases pd\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=un.package\n"
                + "      and ps.ao_server=?\n"
                + "      and un.username=psu.username and ps.pkey=psu.postgres_server\n"
                + "      and pd.datdba=psu.pkey\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one PostgresDatabase on Server="+server);

        // postgres_server_users
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      psu.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      usernames un,\n"
                + "      postgres_servers ps,\n"
                + "      postgres_server_users psu\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=un.package\n"
                + "      and ps.ao_server=?\n"
                + "      and un.username=psu.username and ps.pkey=psu.postgres_server\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one PostgresServerUser on Server="+server);

        // email_domains
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      ed.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      email_domains ed\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=ed.package\n"
                + "      and ed.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one EmailDomain on Server="+server);

        // email_smtp_relays
        if(
            conn.executeBooleanQuery(
                Connection.TRANSACTION_READ_COMMITTED,
                true,
                true,
                "select\n"
                + "  (\n"
                + "    select\n"
                + "      esr.pkey\n"
                + "    from\n"
                + "      packages pk,\n"
                + "      email_smtp_relays esr\n"
                + "    where\n"
                + "      pk.accounting=?\n"
                + "      and pk.name=esr.package\n"
                + "      and esr.ao_server is not null\n"
                + "      and esr.ao_server=?\n"
                + "    limit 1\n"
                + "  )\n"
                + "  is not null\n",
                accounting,
                server
            )
        ) throw new SQLException("Business="+accounting+" still owns at least one EmailSmtpRelay on Server="+server);

        PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false).prepareStatement("delete from business_servers where pkey=?");
        try {
            pstmt.setInt(1, pkey);
            pstmt.executeUpdate();
        } finally {
            pstmt.close();
        }

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, accounting, server, false);
        invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, accounting, server, false);
        invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, accounting, server, false);
    }

    public static void removeDisableLog(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        String accounting=getBusinessForDisableLog(conn, pkey);

        conn.executeUpdate("delete from disable_log where pkey=?", pkey);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.DISABLE_LOG, accounting, InvalidateList.allServers, false);
    }

    public static void setBusinessAccounting(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String oldAccounting,
        String newAccounting
    ) throws IOException, SQLException {
        checkAccessBusiness(conn, source, "setBusinessAccounting", oldAccounting);
        if(!Business.isValidAccounting(newAccounting)) throw new SQLException("Invalid accounting code: "+newAccounting);

        conn.executeUpdate("update businesses set accounting=? where accounting=?", newAccounting, oldAccounting);

        // Notify all clients of the update
        Collection<String> accts=InvalidateList.getCollection(oldAccounting, newAccounting);
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_PROFILES, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.CREDIT_CARDS, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.DISABLE_LOG, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.MONTHLY_CHARGES, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.NOTICE_LOG, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.PACKAGE_DEFINITIONS, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.PACKAGES, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.TICKETS, accts, InvalidateList.allServers, false);
        invalidateList.addTable(conn, SchemaTable.TableID.TRANSACTIONS, accts, InvalidateList.allServers, false);
    }

    public static void setBusinessAdministratorPassword(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String plaintext
    ) throws IOException, SQLException {
        // An administrator may always reset their own passwords
        if(!username.equals(source.getUsername())) checkPermission(conn, source, "setBusinessAdministratorPassword", AOServPermission.Permission.set_business_administrator_password);

        UsernameHandler.checkAccessUsername(conn, source, "setBusinessAdministratorPassword", username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set password for BusinessAdministrator named '"+LinuxAccount.MAIL+'\'');

        if(isBusinessAdministratorDisabled(conn, username)) throw new SQLException("Unable to set password, BusinessAdministrator disabled: "+username);

        if(plaintext!=null && plaintext.length()>0) {
            // Perform the password check here, too.
            PasswordChecker.Result[] results=BusinessAdministrator.checkPassword(username, plaintext);
            if(PasswordChecker.hasResults(results)) throw new SQLException("Invalid password: "+PasswordChecker.getResultsString(results).replace('\n', '|'));
        }

        String encrypted =
            plaintext==null || plaintext.length()==0
            ? BusinessAdministrator.NO_PASSWORD
            : BusinessAdministrator.hash(plaintext)
        ;

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        conn.executeUpdate("update business_administrators set password=? where username=?", encrypted, username);

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
    }
    
    /**
     * Sets a business_administrators profile.
     */
    public static void setBusinessAdministratorProfile(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String username,
        String name,
        String title,
        long birthday,
        boolean isPrivate,
        String workPhone,
        String homePhone,
        String cellPhone,
        String fax,
        String email,
        String address1,
        String address2,
        String city,
        String state,
        String country,
        String zip
    ) throws IOException, SQLException {
        UsernameHandler.checkAccessUsername(conn, source, "setBusinessSdministratorProfile", username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set BusinessAdministrator profile for user '"+LinuxAccount.MAIL+'\'');

        if(!EmailAddress.isValidEmailAddress(email)) throw new SQLException("Invalid format for email: "+email);

        if (country!=null && country.equals(CountryCode.US)) state=convertUSState(conn, state);

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        conn.executeUpdate(
            "update business_administrators set name=?, title=?, birthday=?, private=?, work_phone=?, home_phone=?, cell_phone=?, fax=?, email=?, address1=?, address2=?, city=?, state=?, country=?, zip=? where username=?",
            name,
            title,
            birthday==-1?null:SQLUtility.getDate(birthday),
            isPrivate,
            workPhone,
            homePhone,
            cellPhone,
            fax,
            email,
            address1,
            address2,
            city,
            state,
            country,
            zip,
            username
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
    }
    
    /**
     * Sets the default Server for a Business
     */
    public static void setDefaultBusinessServer(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        String accounting=conn.executeStringQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select accounting from business_servers where pkey=?", pkey);

        checkAccessBusiness(conn, source, "setDefaultBusinessServer", accounting);

        if(isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to set the default BusinessServer, Business disabled: "+accounting);

        // Update the table
        conn.executeUpdate(
            "update business_servers set is_default=true where pkey=?",
            pkey
        );
        conn.executeUpdate(
            "update business_servers set is_default=false where accounting=? and pkey!=?",
            accounting,
            pkey
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.BUSINESS_SERVERS,
            accounting,
            InvalidateList.allServers,
            false
        );
    }
    
    public static BusinessAdministrator getBusinessAdministrator(DatabaseConnection conn, String username) throws IOException, SQLException {
	    synchronized(businessAdministratorsLock) {
            if(businessAdministrators==null) {
                Statement stmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).createStatement();
                try {
                    Map<String,BusinessAdministrator> table=new HashMap<String,BusinessAdministrator>();
                    ResultSet results=stmt.executeQuery("select * from business_administrators");
                    while(results.next()) {
                        BusinessAdministrator ba=new BusinessAdministrator();
                        ba.init(results);
                        table.put(results.getString(1), ba);
                    }
                    businessAdministrators=table;
                } finally {
                    stmt.close();
                }
            }
            return businessAdministrators.get(username);
	    }
    }
    
    public static void invalidateTable(SchemaTable.TableID tableID) {
        if(tableID==SchemaTable.TableID.BUSINESS_ADMINISTRATORS) {
            synchronized(businessAdministratorsLock) {
                businessAdministrators=null;
            }
            synchronized(disabledBusinessAdministrators) {
                disabledBusinessAdministrators.clear();
            }
            synchronized(businessAdministratorDisableLogs) {
                businessAdministratorDisableLogs.clear();
            }
        } else if(tableID==SchemaTable.TableID.BUSINESSES) {
            synchronized(usernameBusinessesLock) {
                usernameBusinesses=null;
            }
            synchronized(disabledBusinesses) {
                disabledBusinesses.clear();
            }
        } else if(tableID==SchemaTable.TableID.BUSINESS_ADMINISTRATOR_PERMISSIONS) {
            synchronized(cachedPermissionsLock) {
                cachedPermissions = null;
            }
        }
    }

    public static String getParentBusiness(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeStringQuery(
            Connection.TRANSACTION_READ_COMMITTED,
            true,
            true,
            "select parent from businesses where accounting=?",
            accounting
        );
    }

    public static String getTechnicalEmail(DatabaseConnection conn, String accountingCode) throws IOException, SQLException {
        return conn.executeStringQuery(
            Connection.TRANSACTION_READ_COMMITTED,
            true,
            true,
            "select technical_email from business_profiles where accounting=? order by priority desc limit 1",
            accountingCode
        );
    }
 
    public static boolean isBusinessAdministrator(DatabaseConnection conn, String username) throws IOException, SQLException {
        return getBusinessAdministrator(conn, username)!=null;
    }

    public static boolean isBusinessAdministratorDisabled(DatabaseConnection conn, String username) throws IOException, SQLException {
        Boolean O;
        synchronized(disabledBusinessAdministrators) {
            O=disabledBusinessAdministrators.get(username);
        }
        if(O!=null) return O.booleanValue();
        boolean isDisabled=getDisableLogForBusinessAdministrator(conn, username)!=-1;
        synchronized(disabledBusinessAdministrators) {
            disabledBusinessAdministrators.put(username, isDisabled);
        }
        return isDisabled;
    }

    public static boolean isBusinessDisabled(DatabaseConnection conn, String accounting) throws IOException, SQLException {
	    synchronized(disabledBusinesses) {
            Boolean O=disabledBusinesses.get(accounting);
            if(O!=null) return O.booleanValue();
            boolean isDisabled=getDisableLogForBusiness(conn, accounting)!=-1;
            disabledBusinesses.put(accounting, isDisabled);
            return isDisabled;
	    }
    }

    public static boolean isBusinessCanceled(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeBooleanQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select canceled is not null from businesses where accounting=?", accounting);
    }

    public static boolean isBusinessBillParent(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeBooleanQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select bill_parent from businesses where accounting=?", accounting);
    }

    public static boolean canSeePrices(DatabaseConnection conn, RequestSource source) throws IOException, SQLException {
        return canSeePrices(conn, UsernameHandler.getBusinessForUsername(conn, source.getUsername()));
    }

    public static boolean canSeePrices(DatabaseConnection conn, String accounting) throws IOException, SQLException {
        return conn.executeBooleanQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select can_see_prices from businesses where accounting=?", accounting);
    }

    public static boolean isBusinessOrParent(DatabaseConnection conn, String parentAccounting, String accounting) throws IOException, SQLException {
        return conn.executeBooleanQuery(Connection.TRANSACTION_READ_COMMITTED, true, true, "select is_business_or_parent(?,?)", parentAccounting, accounting);
    }

    public static boolean canSwitchUser(DatabaseConnection conn, String authenticatedAs, String connectAs) throws IOException, SQLException {
        String authAccounting=UsernameHandler.getBusinessForUsername(conn, authenticatedAs);
        String connectAccounting=UsernameHandler.getBusinessForUsername(conn, connectAs);
        // Cannot switch within same business
        if(authAccounting.equals(connectAccounting)) return false;
        return conn.executeBooleanQuery(
            Connection.TRANSACTION_READ_COMMITTED,
            true,
            true,
            "select\n"
            + "  (select can_switch_users from business_administrators where username=?)\n"
            + "  and is_business_or_parent(?,?)",
            authenticatedAs,
            authAccounting,
            connectAccounting
        );
    }

    /**
     * Gets the list of both technical and billing contacts for all not-canceled businesses.
     *
     * @return  a <code>HashMap</code> of <code>ArrayList</code>
     */
    public static Map<String,List<String>> getBusinessContacts(DatabaseConnection conn) throws IOException, SQLException {
        // Load the list of businesses and their contacts
        Map<String,List<String>> businessContacts=new HashMap<String,List<String>>();
        List<String> foundAddresses=new SortedArrayList<String>();
        PreparedStatement pstmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement("select bp.accounting, bp.billing_email, bp.technical_email from business_profiles bp, businesses bu where bp.accounting=bu.accounting and bu.canceled is null order by bp.accounting, bp.priority desc");
        try {
            ResultSet results=pstmt.executeQuery();
            try {
                while(results.next()) {
                    String accounting=results.getString(1);
                    if(!businessContacts.containsKey(accounting)) {
                        List<String> uniqueAddresses=new ArrayList<String>();
                        foundAddresses.clear();
                        // billing contacts
                        List<String> addresses=StringUtility.splitStringCommaSpace(results.getString(2));
                        for(int c=0;c<addresses.size();c++) {
                            String addy=addresses.get(c).toLowerCase();
                            if(!foundAddresses.contains(addy)) {
                                uniqueAddresses.add(addy);
                                foundAddresses.add(addy);
                            }
                        }
                        // technical contacts
                        addresses=StringUtility.splitStringCommaSpace(results.getString(3));
                        for(int c=0;c<addresses.size();c++) {
                            String addy=addresses.get(c).toLowerCase();
                            if(!foundAddresses.contains(addy)) {
                                uniqueAddresses.add(addy);
                                foundAddresses.add(addy);
                            }
                        }
                        businessContacts.put(accounting, uniqueAddresses);
                    }
                }
            } finally {
                results.close();
            }
        } catch(SQLException err) {
            throw new WrappedSQLException(err, pstmt);
        } finally {
            pstmt.close();
        }
        return businessContacts;
    }

    /**
     * Gets the best estimate of a business for a list of email addresses or <code>null</code> if can't determine.
     * The algorithm takes these steps.
     * <ol>
     *   <li>Look for exact matches in billing and technical contacts, with a weight of 10.</li>
     *   <li>Look for matches in email_domains, with a weight of 5</li>
     *   <li>Look for matches in httpd_site_urls with a weight of 1</li>
     *   <li>Look for matches in dns_zones with a weight of 1</li>
     *   <li>Add up the weights per business</li>
     *   <li>Find the highest weight</li>
     *   <li>Follow the bill_parents up to top billing level</li>
     * </ol>
     */
    public static String getBusinessFromEmailAddresses(DatabaseConnection conn, List<String> addresses) throws IOException, SQLException {
        // Load the list of businesses and their contacts
        Map<String,List<String>> businessContacts=getBusinessContacts(conn);

        // The cumulative weights are added up here, per business
        Map<String,Integer> businessWeights=new HashMap<String,Integer>();

        // Go through all addresses
        for(int c=0;c<addresses.size();c++) {
            String address=addresses.get(c).toLowerCase();
            // Look for billing and technical contact matches, 10 points each
            Iterator<String> I=businessContacts.keySet().iterator();
            while(I.hasNext()) {
                String accounting=I.next();
                List<String> list=businessContacts.get(accounting);
                for(int d=0;d<list.size();d++) {
                    String contact=list.get(d);
                    if(address.equals(contact)) addWeight(businessWeights, accounting, 10);
                }
            }

            // Parse the domain
            int pos=address.lastIndexOf('@');
            if(pos!=-1) {
                String domain=address.substring(pos+1);
                if(domain.length()>0) {
                    // Look for matches in email_domains, 5 points each
                    List<String> domains=conn.executeStringListQuery(
                        Connection.TRANSACTION_READ_COMMITTED,
                        true,
                        "select\n"
                        + "  pk.accounting\n"
                        + "from\n"
                        + "  email_domains ed,\n"
                        + "  packages pk\n"
                        + "where\n"
                        + "  ed.domain=?\n"
                        + "  and ed.package=pk.name",
                        domain
                    );
                    for(int d=0;d<domains.size();d++) {
                        String accounting=domains.get(d);
                        addWeight(businessWeights, accounting, 5);
                    }
                    // Look for matches in httpd_site_urls, 1 point each
                    List<String> sites=conn.executeStringListQuery(
                        Connection.TRANSACTION_READ_COMMITTED,
                        true,
                        "select\n"
                        + "  pk.accounting\n"
                        + "from\n"
                        + "  httpd_site_urls hsu,\n"
                        + "  httpd_site_binds hsb,\n"
                        + "  httpd_sites hs,\n"
                        + "  packages pk\n"
                        + "where\n"
                        + "  hsu.hostname=?\n"
                        + "  and hsu.httpd_site_bind=hsb.pkey\n"
                        + "  and hsb.httpd_site=hs.pkey\n"
                        + "  and hs.package=pk.name",
                        domain
                    );
                    for(int d=0;d<sites.size();d++) {
                        String accounting=sites.get(d);
                        addWeight(businessWeights, accounting, 1);
                    }
                    // Look for matches in dns_zones, 1 point each
                    List<String> zones=conn.executeStringListQuery(
                        Connection.TRANSACTION_READ_COMMITTED,
                        true,
                        "select\n"
                        + "  pk.accounting\n"
                        + "from\n"
                        + "  dns_zones dz,\n"
                        + "  packages pk\n"
                        + "where\n"
                        + "  dz.zone=?\n"
                        + "  and dz.package=pk.name",
                        domain
                    );
                    for(int d=0;d<zones.size();d++) {
                        String accounting=zones.get(d);
                        addWeight(businessWeights, accounting, 1);
                    }
                }
            }
        }

        // Find the highest weight
        Iterator<String> I=businessWeights.keySet().iterator();
        int highest=0;
        String highestAccounting=null;
        while(I.hasNext()) {
            String accounting=I.next();
            int weight=businessWeights.get(accounting).intValue();
            if(weight>highest) {
                highest=weight;
                highestAccounting=accounting;
            }
        }

        // Follow the bill_parent flags toward the top, but skipping canceled
        while(
            highestAccounting!=null
            && (
                isBusinessCanceled(conn, highestAccounting)
                || isBusinessBillParent(conn, highestAccounting)
            )
        ) {
            highestAccounting=getParentBusiness(conn, highestAccounting);
        }

        // Do not accept root business
        if(highestAccounting!=null && highestAccounting.equals(getRootBusiness())) highestAccounting=null;

        // Return result
        return highestAccounting;
    }

    private static void addWeight(Map<String,Integer> businessWeights, String accounting, int weight) {
        Integer I=businessWeights.get(accounting);
        int previous=I==null ? 0 : I.intValue();
        businessWeights.put(accounting, Integer.valueOf(previous + weight));
    }

    public static boolean canBusinessAccessServer(DatabaseConnection conn, String accounting, int server) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            Connection.TRANSACTION_READ_COMMITTED,
            true,
            true,
            "select\n"
            + "  (\n"
            + "    select\n"
            + "      pkey\n"
            + "    from\n"
            + "      business_servers\n"
            + "    where\n"
            + "      accounting=?\n"
            + "      and server=?\n"
            + "    limit 1\n"
            + "  )\n"
            + "  is not null\n",
            accounting,
            server
        );
    }
    
    public static void checkBusinessAccessServer(DatabaseConnection conn, RequestSource source, String action, String accounting, int server) throws IOException, SQLException {
        if(!canBusinessAccessServer(conn, accounting, server)) {
            String message=
            "accounting="
            +accounting
            +" is not allowed to access server.pkey="
            +server
            +": action='"
            +action
            +"'"
            ;
            throw new SQLException(message);
        }
    }
}