package com.aoindustries.aoserv.master;

/*
 * Copyright 2001-2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServPermission;
import com.aoindustries.aoserv.client.AOServProtocol;
import com.aoindustries.aoserv.client.FailoverMySQLReplication;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.MasterUser;
import com.aoindustries.aoserv.client.MySQLDatabase;
import com.aoindustries.aoserv.client.MySQLDatabaseTable;
import com.aoindustries.aoserv.client.MySQLServer;
import com.aoindustries.aoserv.client.MySQLUser;
import com.aoindustries.aoserv.client.PasswordChecker;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The <code>MySQLHandler</code> handles all the accesses to the MySQL tables.
 *
 * @author  AO Industries, Inc.
 */
final public class MySQLHandler {

    private MySQLHandler() {
    }

    private final static Map<Integer,Boolean> disabledMySQLUsers=new HashMap<Integer,Boolean>();

    public static void checkAccessMySQLDatabase(DatabaseConnection conn, RequestSource source, String action, int mysql_database) throws IOException, SQLException {
        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
        if(mu!=null) {
            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                int mysqlServer=getMySQLServerForMySQLDatabase(conn, mysql_database);
                int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
                ServerHandler.checkAccessServer(conn, source, action, aoServer);
            }
        } else {
            BusinessHandler.checkAccessBusiness(conn, source, action, getBusinessForMySQLDatabase(conn, mysql_database));
        }
    }

    public static void checkAccessMySQLDBUser(DatabaseConnection conn, RequestSource source, String action, int pkey) throws IOException, SQLException {
        checkAccessMySQLDatabase(conn, source, action, getMySQLDatabaseForMySQLDBUser(conn, pkey));
        checkAccessMySQLUser(conn, source, action, getMySQLUserForMySQLDBUser(conn, pkey));
    }

    public static void checkAccessMySQLUser(DatabaseConnection conn, RequestSource source, String action, int pkey) throws IOException, SQLException {
        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
        if(mu!=null) {
            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                int mysqlServer = getMySQLServerForMySQLUser(conn, pkey);
                int aoServer = getAOServerForMySQLServer(conn, mysqlServer);
                ServerHandler.checkAccessServer(conn, source, action, aoServer);
            }
        } else {
            UsernameHandler.checkAccessUsername(conn, source, action, getUsernameForMySQLUser(conn, pkey));
        }
    }

    public static void checkAccessMySQLServer(DatabaseConnection conn, RequestSource source, String action, int mysql_server) throws IOException, SQLException {
        MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
        if(mu!=null) {
            if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
                // Protect by server
                int aoServer = getAOServerForMySQLServer(conn, mysql_server);
                ServerHandler.checkAccessServer(conn, source, action, aoServer);
            }
        } else {
            // Protect by package
            BusinessHandler.checkAccessBusiness(conn, source, action, getBusinessForMySQLServer(conn, mysql_server));
        }
    }

    /**
     * Adds a MySQL database to the system.
     */
    public static int addMySQLDatabase(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String name,
        int mysqlServer,
        String accounting
    ) throws IOException, SQLException {
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);

        BusinessHandler.checkBusinessAccessServer(conn, source, "addMySQLDatabase", accounting, aoServer);
        if(BusinessHandler.isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to add MySQLDatabase '"+name+"', Business disabled: "+accounting);

        // Must be a valid name format
        List<String> reservedWords=getReservedWords(conn);
        String invalidReason = MySQLDatabaseTable.isValidDatabaseName(Locale.getDefault(), name, reservedWords);
        if(invalidReason!=null) throw new SQLException(invalidReason);

        // Must be allowed to access this server and business
        ServerHandler.checkAccessServer(conn, source, "addMySQLDatabase", aoServer);
        BusinessHandler.checkAccessBusiness(conn, source, "addMySQLDatabase", accounting);

        // Add the entry to the database
        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('mysql_databases_pkey_seq')");
        conn.executeUpdate(
            "insert into\n"
            + "  mysql_databases\n"
            + "values(\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?\n"
            + ")",
            pkey,
            name,
            mysqlServer,
            accounting
        );

        // Notify all clients of the update, the server will detect this change and automatically add the database
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_DATABASES,
            accounting,
            aoServer,
            false
        );
        return pkey;
    }

    /**
     * Grants a MySQLUser access to a MySQLMasterDatabase.getDatabase().
     */
    public static int addMySQLDBUser(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int mysql_database,
        int mysql_user,
        boolean canSelect,
        boolean canInsert,
        boolean canUpdate,
        boolean canDelete,
        boolean canCreate,
        boolean canDrop,
        boolean canIndex,
        boolean canAlter,
        boolean canCreateTempTable,
        boolean canLockTables,
        boolean canCreateView,
        boolean canShowView,
        boolean canCreateRoutine,
        boolean canAlterRoutine,
        boolean canExecute,
        boolean canEvent,
        boolean canTrigger
    ) throws IOException, SQLException {
        // Must be allowed to access this database and user
        checkAccessMySQLDatabase(conn, source, "addMySQLDBUser", mysql_database);
        checkAccessMySQLUser(conn, source, "addMySQLDBUser", mysql_user);
        if(isMySQLUserDisabled(conn, mysql_user)) throw new SQLException("Unable to add MySQLDBUser, MySQLUser disabled: "+mysql_user);
        int mysql_server = getMySQLServerForMySQLDatabase(conn, mysql_database);

        // Add the entry to the database
        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('mysql_db_users_pkey_seq')");
        conn.executeUpdate(
            "insert into mysql_db_users values(?,?,?,?,?,?,?,?,?,?,false,false,?,?,?,?,?,?,?,?,?,?,?)",
            pkey,
            mysql_server,
            mysql_database,
            mysql_user,
            canSelect,
            canInsert,
            canUpdate,
            canDelete,
            canCreate,
            canDrop,
            canIndex,
            canAlter,
            canCreateTempTable,
            canLockTables,
            canCreateView,
            canShowView,
            canCreateRoutine,
            canAlterRoutine,
            canExecute,
            canEvent,
            canTrigger
        );

        // Notify all clients of the update, the server will detect this change and automatically update MySQL
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_DB_USERS,
            getBusinessForMySQLUser(conn, mysql_user),
            getAOServerForMySQLServer(conn, mysql_server),
            false
        );
        return pkey;
    }

    /**
     * Adds a MySQL user.
     */
    public static int addMySQLUser(
        DatabaseConnection conn,
        RequestSource source, 
        InvalidateList invalidateList,
        String username,
        int mysqlServer,
        String host
    ) throws IOException, SQLException {
        UsernameHandler.checkAccessUsername(conn, source, "addMySQLUser", username);
        if(UsernameHandler.isUsernameDisabled(conn, username)) throw new SQLException("Unable to add MySQLUser, Username disabled: "+username);
        if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to add MySQLUser for user '"+LinuxAccount.MAIL+'\'');
        if(!MySQLUser.isValidUsername(username)) throw new SQLException("Invalid MySQLUser username: "+username);
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        ServerHandler.checkAccessServer(conn, source, "addMySQLUser", aoServer);
        // This sub-account must have access to the server
        UsernameHandler.checkUsernameAccessServer(conn, source, "addMySQLUser", username, aoServer);

        Boolean isRoot = username.equals(MySQLUser.ROOT);
        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('mysql_users_pkey_seq')");
        conn.executeUpdate(
            "insert into mysql_users values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,null,null,?,?,?,?)",
            pkey,
            username,
            mysqlServer,
            host,
            isRoot, // select_priv
            isRoot, // insert_priv
            isRoot, // update_priv
            isRoot, // delete_priv
            isRoot, // create_priv
            isRoot, // drop_priv
            isRoot, // reload_priv
            isRoot, // shutdown_priv
            isRoot, // process_priv
            isRoot, // file_priv
            isRoot, // grant_priv
            isRoot, // references_priv
            isRoot, // index_priv
            isRoot, // alter_priv
            isRoot, // show_db_priv
            isRoot, // super_priv
            isRoot, // create_tmp_table_priv
            isRoot, // lock_tables_priv
            isRoot, // execute_priv
            isRoot, // repl_slave_priv
            isRoot, // repl_client_priv
            isRoot, // create_view_priv
            isRoot, // show_view_priv
            isRoot, // create_routine_priv
            isRoot, // alter_routine_priv
            isRoot, // create_user_priv
            isRoot, // event_priv
            isRoot, // trigger_priv
            isRoot?MySQLUser.UNLIMITED_QUESTIONS        : MySQLUser.DEFAULT_MAX_QUESTIONS,
            isRoot?MySQLUser.UNLIMITED_UPDATES          : MySQLUser.DEFAULT_MAX_UPDATES,
            isRoot?MySQLUser.UNLIMITED_CONNECTIONS      : MySQLUser.DEFAULT_MAX_CONNECTIONS,
            isRoot?MySQLUser.UNLIMITED_USER_CONNECTIONS : MySQLUser.DEFAULT_MAX_USER_CONNECTIONS
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_USERS,
            UsernameHandler.getBusinessForUsername(conn, username),
            aoServer,
            true
        );

        return pkey;
    }

    public static void disableMySQLUser(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int disableLog,
        int pkey
    ) throws IOException, SQLException {
        if(isMySQLUserDisabled(conn, pkey)) throw new SQLException("MySQLUser is already disabled: "+pkey);
        BusinessHandler.checkAccessDisableLog(conn, source, "disableMySQLUser", disableLog, false);
        checkAccessMySQLUser(conn, source, "disableMySQLUser", pkey);

        conn.executeUpdate(
            "update mysql_users set disable_log=? where pkey=?",
            disableLog,
            pkey
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_USERS,
            getBusinessForMySQLUser(conn, pkey),
            getAOServerForMySQLServer(conn, getMySQLServerForMySQLUser(conn, pkey)),
            false
        );
    }

    /**
     * Dumps a MySQL database
     */
    public static void dumpMySQLDatabase(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        int dbPKey
    ) throws IOException, SQLException {
        checkAccessMySQLDatabase(conn, source, "dumpMySQLDatabase", dbPKey);

        int mysqlServer=getMySQLServerForMySQLDatabase(conn, dbPKey);
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).dumpMySQLDatabase(dbPKey, out);
    }

    public static void enableMySQLUser(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        int disableLog=getDisableLogForMySQLUser(conn, pkey);
        if(disableLog==-1) throw new SQLException("MySQLUser is already enabled: "+pkey);
        BusinessHandler.checkAccessDisableLog(conn, source, "enableMySQLUser", disableLog, true);
        checkAccessMySQLUser(conn, source, "enableMySQLUser", pkey);
        String username = getUsernameForMySQLUser(conn, pkey);
        UsernameHandler.checkAccessUsername(conn, source, "enableMySQLUser", username);
        if(UsernameHandler.isUsernameDisabled(conn, username)) throw new SQLException("Unable to enable MySQLUser "+pkey+", Username not enabled: "+username);

        conn.executeUpdate(
            "update mysql_users set disable_log=null where pkey=?",
            pkey
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_USERS,
            username,
            getAOServerForMySQLServer(conn, getMySQLServerForMySQLUser(conn, pkey)),
            false
        );
    }

    /**
     * Generates a unique MySQL database name.
     */
    public static String generateMySQLDatabaseName(
        DatabaseConnection conn,
        String template_base,
        String template_added
    ) throws IOException, SQLException {
        List<String> reservedWords=getReservedWords(conn);

        // Load the entire list of mysql database names
        List<String> names=conn.executeStringListQuery("select name from mysql_databases group by name");
        int size=names.size();

        // Sort them
        List<String> sorted=new SortedArrayList<String>(size);
        sorted.addAll(names);

        // Find one that is not used
        String goodOne=null;
        for(int c=0;c<Integer.MAX_VALUE;c++) {
            String name= (c==0) ? template_base : (template_base+template_added+c);
            String invalidReason = MySQLDatabaseTable.isValidDatabaseName(Locale.getDefault(), name, reservedWords);
            if(invalidReason!=null) throw new SQLException(invalidReason);
            if(!sorted.contains(name)) {
                goodOne=name;
                break;
            }
        }

        // If could not find one, report and error
        if(goodOne==null) throw new SQLException("Unable to find available MySQL database name for template_base="+template_base+" and template_added="+template_added);
        return goodOne;
    }

    private static final Object reservedWordLock=new Object();
    private static List<String> reservedWordCache;
    public static List<String> getReservedWords(DatabaseConnection conn) throws IOException, SQLException {
        synchronized(reservedWordLock) {
            if(reservedWordCache==null) {
                // Load the list of reserved words
                reservedWordCache=conn.executeStringListQuery("select word from mysql_reserved_words");
            }
            return reservedWordCache;
        }
    }

    public static int getDisableLogForMySQLUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select coalesce(disable_log, -1) from mysql_users where pkey=?", pkey);
    }

    public static String getUsernameForMySQLUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery("select username from mysql_users where pkey=?", pkey);
    }

    public static String getMySQLDatabaseName(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery("select name from mysql_databases where pkey=?", pkey);
    }

    public static void invalidateTable(SchemaTable.TableID tableID) {
        switch(tableID) {
            case MYSQL_RESERVED_WORDS :
                synchronized(reservedWordLock) {
                    reservedWordCache=null;
                }
                break;
            case MYSQL_USERS :
                synchronized(MySQLHandler.class) {
                    disabledMySQLUsers.clear();
                }
                break;
        }
    }

    public static boolean isMySQLUser(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            "select\n"
            + "  (\n"
            + "    select\n"
            + "      username\n"
            + "    from\n"
            + "      mysql_users\n"
            + "    where\n"
            + "      username=?\n"
            + "    limit 1\n"
            + "  ) is not null",
            username
        );
    }

    public static boolean isMySQLUserDisabled(DatabaseConnection conn, int pkey) throws IOException, SQLException {
	    synchronized(MySQLHandler.class) {
            Boolean O=disabledMySQLUsers.get(pkey);
            if(O!=null) return O.booleanValue();
            boolean isDisabled=getDisableLogForMySQLUser(conn, pkey)!=-1;
            disabledMySQLUsers.put(pkey, isDisabled);
            return isDisabled;
	    }
    }

    /**
     * Determines if a MySQL database name is available.
     */
    public static boolean isMySQLDatabaseNameAvailable(
        DatabaseConnection conn,
        RequestSource source,
        String name,
        int mysqlServer
    ) throws IOException, SQLException {
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        ServerHandler.checkAccessServer(conn, source, "isMySQLDatabaseNameAvailable", aoServer);
        return conn.executeBooleanQuery("select (select pkey from mysql_databases where name=? and mysql_server=?) is null", name, mysqlServer);
    }

    public static boolean isMySQLUserPasswordSet(
        DatabaseConnection conn,
        RequestSource source,
        int pkey
    ) throws IOException, SQLException {
        checkAccessMySQLUser(conn, source, "isMySQLUserPasswordSet", pkey);
        if(isMySQLUserDisabled(conn, pkey)) throw new SQLException("Unable to determine if the MySQLUser password is set, account disabled: "+pkey);
        String username=getUsernameForMySQLUser(conn, pkey);
        int mysqlServer=getMySQLServerForMySQLUser(conn, pkey);
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        String password=DaemonHandler.getDaemonConnector(conn, aoServer).getEncryptedMySQLUserPassword(mysqlServer, username);
        return !MySQLUser.NO_PASSWORD_DB_VALUE.equals(password);
    }

    /**
     * Removes a MySQLDatabase from the system.
     */
    public static void removeMySQLDatabase(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        checkAccessMySQLDatabase(conn, source, "removeMySQLDatabase", pkey);

        removeMySQLDatabase(conn, invalidateList, pkey);
    }

    /**
     * Removes a MySQLDatabase from the system.
     */
    public static void removeMySQLDatabase(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        // Cannot remove the mysql database
        String dbName=getMySQLDatabaseName(conn, pkey);
        if(dbName.equals(MySQLDatabase.MYSQL)) throw new SQLException("Not allowed to remove the database named '"+MySQLDatabase.MYSQL+'\'');

        // Remove the mysql_db_user entries
        List<String> dbUserAccounts=conn.executeStringListQuery(
            "select\n"
            + "  un.accounting\n"
            + "from\n"
            + "  mysql_db_users mdu,\n"
            + "  mysql_users mu,\n"
            + "  usernames un\n"
            + "where\n"
            + "  mdu.mysql_database=?\n"
            + "  and mdu.mysql_user=mu.pkey\n"
            + "  and mu.username=un.username\n"
            + "group by\n"
            + "  un.accounting",
            pkey
        );
        if(dbUserAccounts.size()>0) conn.executeUpdate("delete from mysql_db_users where mysql_database=?", pkey);

        // Remove the database entry
        String accounting=getBusinessForMySQLDatabase(conn, pkey);
        int mysqlServer=getMySQLServerForMySQLDatabase(conn, pkey);
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        conn.executeUpdate("delete from mysql_databases where pkey=?", pkey);

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_DATABASES,
            accounting,
            aoServer,
            false
        );
        if(dbUserAccounts.size()>0) invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_DB_USERS,
            dbUserAccounts,
            aoServer,
            false
        );
    }

    /**
     * Removes a MySQLDBUser from the system.
     */
    public static void removeMySQLDBUser(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        checkAccessMySQLDBUser(conn, source, "removeMySQLDBUser", pkey);

        // Remove the mysql_db_user
        String accounting=getBusinessForMySQLDBUser(conn, pkey);
        int mysqlServer=getMySQLServerForMySQLDBUser(conn, pkey);
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        conn.executeUpdate("delete from mysql_db_users where pkey=?", pkey);

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_DB_USERS,
            accounting,
            aoServer,
            false
        );
    }

    /**
     * Removes a MySQLUser from the system.
     */
    public static void removeMySQLUser(
        DatabaseConnection conn,
        RequestSource source, 
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        checkAccessMySQLUser(conn, source, "removeMySQLUser", pkey);

        removeMySQLUser(conn, invalidateList, pkey);
    }

    /**
     * Removes a MySQLUser from the system.
     */
    public static void removeMySQLUser(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        String username = getUsernameForMySQLUser(conn, pkey);
        if(username.equals(MySQLUser.ROOT)) throw new SQLException("Not allowed to remove MySQLUser for user '"+MySQLUser.ROOT+'\'');

        String accounting=UsernameHandler.getBusinessForUsername(conn, username);
        int mysqlServer = getMySQLServerForMySQLUser(conn, pkey);
        int aoServer = getAOServerForMySQLServer(conn, mysqlServer);

        // Remove the mysql_db_user
        if(conn.executeUpdate("delete from mysql_db_users where mysql_user=?", pkey)>0) {
            invalidateList.addTable(
                conn,
                SchemaTable.TableID.MYSQL_DB_USERS,
                accounting,
                aoServer,
                false
            );
        }

        // Remove the mysql_user
        conn.executeUpdate("delete from mysql_users where pkey=?", pkey);
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_USERS,
            accounting,
            aoServer,
            false
        );
    }

    /**
     * Sets a MySQL password.
     */
    public static void setMySQLUserPassword(
        DatabaseConnection conn,
        RequestSource source,
        int pkey,
        String password
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "setMySQLUserPassword", AOServPermission.Permission.set_mysql_user_password);
        checkAccessMySQLUser(conn, source, "setMySQLUserPassword", pkey);
        if(isMySQLUserDisabled(conn, pkey)) throw new SQLException("Unable to set MySQLUser password, account disabled: "+pkey);

        // Get the server, username for the user
        String username=getUsernameForMySQLUser(conn, pkey);

        // No setting the super user password
        if(username.equals(MySQLUser.ROOT)) throw new SQLException("The MySQL "+MySQLUser.ROOT+" password may not be set.");

        // Perform the password check here, too.
        if(password!=null && password.length()==0) password=MySQLUser.NO_PASSWORD;
        if(password!=MySQLUser.NO_PASSWORD) {
            PasswordChecker.Result[] results = MySQLUser.checkPassword(Locale.getDefault(), username, password);
            if(PasswordChecker.hasResults(Locale.getDefault(), results)) throw new SQLException("Invalid password: "+PasswordChecker.getResultsString(results).replace('\n', '|'));
        }

        // Contact the daemon for the update
        int mysqlServer=getMySQLServerForMySQLUser(conn, pkey);
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).setMySQLUserPassword(mysqlServer, username, password);
    }

    public static void setMySQLUserPredisablePassword(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String password
    ) throws IOException, SQLException {
        checkAccessMySQLUser(conn, source, "setMySQLUserPredisablePassword", pkey);
        if(password==null) {
            if(isMySQLUserDisabled(conn, pkey)) throw new SQLException("Unable to clear MySQLUser predisable password, account disabled: "+pkey);
        } else {
            if(!isMySQLUserDisabled(conn, pkey)) throw new SQLException("Unable to set MySQLUser predisable password, account not disabled: "+pkey);
        }

        // Update the database
        conn.executeUpdate(
            "update mysql_users set predisable_password=? where pkey=?",
            password,
            pkey
        );

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.MYSQL_USERS,
            getBusinessForMySQLUser(conn, pkey),
            getAOServerForMySQLServer(conn, getMySQLServerForMySQLUser(conn, pkey)),
            false
        );
    }

    /**
     * Waits for any pending or processing MySQL database config rebuild to complete.
     */
    public static void waitForMySQLDatabaseRebuild(
        DatabaseConnection conn,
        RequestSource source,
        int aoServer
    ) throws IOException, SQLException {
        ServerHandler.checkAccessServer(conn, source, "waitForMySQLDatabaseRebuild", aoServer);
        ServerHandler.waitForInvalidates(aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).waitForMySQLDatabaseRebuild();
    }

    /**
     * Waits for any pending or processing MySQL database config rebuild to complete.
     */
    public static void waitForMySQLDBUserRebuild(
        DatabaseConnection conn,
        RequestSource source,
        int aoServer
    ) throws IOException, SQLException {
        ServerHandler.checkAccessServer(conn, source, "waitForMySQLDBUserRebuild", aoServer);
        ServerHandler.waitForInvalidates(aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).waitForMySQLDBUserRebuild();
    }

    /**
     * Waits for any pending or processing MySQL database config rebuild to complete.
     */
    public static void waitForMySQLUserRebuild(
        DatabaseConnection conn,
        RequestSource source,
        int aoServer
    ) throws IOException, SQLException {
        ServerHandler.checkAccessServer(conn, source, "waitForMySQLUserRebuild", aoServer);
        ServerHandler.waitForInvalidates(aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).waitForMySQLUserRebuild();
    }

    public static String getBusinessForMySQLDatabase(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery("select accounting from mysql_databases where pkey=?", pkey);
    }

    public static String getBusinessForMySQLDBUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  un.accounting\n"
            + "from\n"
            + "  mysql_db_users mdu,\n"
            + "  mysql_users mu,\n"
            + "  usernames un\n"
            + "where\n"
            + "  mdu.pkey=?\n"
            + "  and mdu.mysql_user=mu.pkey\n"
            + "  and mu.username=un.username",
            pkey
        );
    }

    public static String getBusinessForMySQLUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeStringQuery(
            "select\n"
            + "  un.accounting\n"
            + "from\n"
            + "  mysql_users mu,\n"
            + "  usernames un\n"
            + "where\n"
            + "  mu.username=un.username\n"
            + "  and mu.pkey=?",
            pkey
        );
    }

    public static IntList getMySQLUsersForUsername(DatabaseConnection conn, String username) throws IOException, SQLException {
        return conn.executeIntListQuery("select pkey from mysql_users where username=?", username);
    }

    public static int getMySQLServerForMySQLDatabase(DatabaseConnection conn, int mysql_database) throws IOException, SQLException {
        return conn.executeIntQuery("select mysql_server from mysql_databases where pkey=?", mysql_database);
    }

    public static int getAOServerForMySQLServer(DatabaseConnection conn, int mysqlServer) throws IOException, SQLException {
        return conn.executeIntQuery("select ao_server from mysql_servers where pkey=?", mysqlServer);
    }

    public static String getBusinessForMySQLServer(DatabaseConnection conn, int mysqlServer) throws IOException, SQLException {
        return conn.executeStringQuery("select accounting from mysql_servers where pkey=?", mysqlServer);
    }

    public static int getPortForMySQLServer(DatabaseConnection conn, int mysqlServer) throws IOException, SQLException {
        return conn.executeIntQuery("select nb.port from mysql_servers ms inner join net_binds nb on ms.net_bind=nb.pkey where ms.pkey=?", mysqlServer);
    }

    public static int getMySQLServerForFailoverMySQLReplication(DatabaseConnection conn, int failoverMySQLReplication) throws IOException, SQLException {
        return conn.executeIntQuery("select mysql_server from failover_mysql_replications where pkey=?", failoverMySQLReplication);
    }

    public static int getMySQLServerForMySQLDBUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select mysql_server from mysql_db_users where pkey=?", pkey);
    }

    public static int getMySQLDatabaseForMySQLDBUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select mysql_database from mysql_db_users where pkey=?", pkey);
    }

    public static int getMySQLUserForMySQLDBUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select mysql_user from mysql_db_users where pkey=?", pkey);
    }

    public static int getMySQLServerForMySQLUser(DatabaseConnection conn, int pkey) throws IOException, SQLException {
        return conn.executeIntQuery("select mysql_server from mysql_users where pkey=?", pkey);
    }

    public static void restartMySQL(
        DatabaseConnection conn,
        RequestSource source,
        int mysqlServer
    ) throws IOException, SQLException {
        int aoServer=getAOServerForMySQLServer(conn, mysqlServer);
        boolean canControl=BusinessHandler.canBusinessServer(conn, source, aoServer, "can_control_mysql");
        if(!canControl) throw new SQLException("Not allowed to restart MySQL on "+aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).restartMySQL(mysqlServer);
    }

    public static void startMySQL(
        DatabaseConnection conn,
        RequestSource source,
        int aoServer
    ) throws IOException, SQLException {
        boolean canControl=BusinessHandler.canBusinessServer(conn, source, aoServer, "can_control_mysql");
        if(!canControl) throw new SQLException("Not allowed to start MySQL on "+aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).startMySQL();
    }

    public static void stopMySQL(
        DatabaseConnection conn,
        RequestSource source,
        int aoServer
    ) throws IOException, SQLException {
        boolean canControl=BusinessHandler.canBusinessServer(conn, source, aoServer, "can_control_mysql");
        if(!canControl) throw new SQLException("Not allowed to stop MySQL on "+aoServer);
        DaemonHandler.getDaemonConnector(conn, aoServer).stopMySQL();
    }
    
    public static void getMasterStatus(
        DatabaseConnection conn,
        RequestSource source,
        int mysqlServer,
        CompressedDataOutputStream out
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "getMasterStatus", AOServPermission.Permission.get_mysql_master_status);
        // Check access
        checkAccessMySQLServer(conn, source, "getMasterStatus", mysqlServer);
        int aoServer = getAOServerForMySQLServer(conn, mysqlServer);
        MySQLServer.MasterStatus masterStatus = DaemonHandler.getDaemonConnector(conn, aoServer).getMySQLMasterStatus(
            mysqlServer
        );
        if(masterStatus==null) out.writeByte(AOServProtocol.DONE);
        else {
            out.writeByte(AOServProtocol.NEXT);
            out.writeNullUTF(masterStatus.getFile());
            out.writeNullUTF(masterStatus.getPosition());
        }
    }

    public static void getSlaveStatus(
        DatabaseConnection conn,
        RequestSource source,
        int failoverMySQLReplication,
        CompressedDataOutputStream out
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "getSlaveStatus", AOServPermission.Permission.get_mysql_slave_status);
        // Check access
        int mysqlServer = getMySQLServerForFailoverMySQLReplication(conn, failoverMySQLReplication);
        checkAccessMySQLServer(conn, source, "getSlaveStatus", mysqlServer);
        int daemonServer;
        String chrootPath;
        int osv;
        if(conn.executeBooleanQuery("select ao_server is not null from failover_mysql_replications where pkey=?", failoverMySQLReplication)) {
            // ao_server-based
            daemonServer = conn.executeIntQuery("select ao_server from failover_mysql_replications where pkey=?", failoverMySQLReplication);
            chrootPath = "";
            osv = ServerHandler.getOperatingSystemVersionForServer(conn, daemonServer);
            if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+daemonServer);
        } else {
            // replication-based
            daemonServer = conn.executeIntQuery("select bp.ao_server from failover_mysql_replications fmr inner join failover_file_replications ffr on fmr.replication=ffr.pkey inner join backup_partitions bp on ffr.backup_partition=bp.pkey where fmr.pkey=?", failoverMySQLReplication);
            String toPath = conn.executeStringQuery("select bp.path from failover_mysql_replications fmr inner join failover_file_replications ffr on fmr.replication=ffr.pkey inner join backup_partitions bp on ffr.backup_partition=bp.pkey where fmr.pkey=?", failoverMySQLReplication);
            int aoServer = getAOServerForMySQLServer(conn, mysqlServer);
            osv = ServerHandler.getOperatingSystemVersionForServer(conn, aoServer);
            if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+aoServer);
            chrootPath = toPath+"/"+ServerHandler.getHostnameForAOServer(conn, aoServer);
        }
        FailoverMySQLReplication.SlaveStatus slaveStatus = DaemonHandler.getDaemonConnector(conn, daemonServer).getMySQLSlaveStatus(
            chrootPath,
            osv,
            getPortForMySQLServer(conn, mysqlServer)
        );
        if(slaveStatus==null) out.writeByte(AOServProtocol.DONE);
        else {
            out.writeByte(AOServProtocol.NEXT);
            out.writeNullUTF(slaveStatus.getSlaveIOState());
            out.writeNullUTF(slaveStatus.getMasterLogFile());
            out.writeNullUTF(slaveStatus.getReadMasterLogPos());
            out.writeNullUTF(slaveStatus.getRelayLogFile());
            out.writeNullUTF(slaveStatus.getRelayLogPos());
            out.writeNullUTF(slaveStatus.getRelayMasterLogFile());
            out.writeNullUTF(slaveStatus.getSlaveIORunning());
            out.writeNullUTF(slaveStatus.getSlaveSQLRunning());
            out.writeNullUTF(slaveStatus.getLastErrno());
            out.writeNullUTF(slaveStatus.getLastError());
            out.writeNullUTF(slaveStatus.getSkipCounter());
            out.writeNullUTF(slaveStatus.getExecMasterLogPos());
            out.writeNullUTF(slaveStatus.getRelayLogSpace());
            out.writeNullUTF(slaveStatus.getSecondsBehindMaster());
        }
    }

    public static void getTableStatus(
        DatabaseConnection conn,
        RequestSource source,
        int mysqlDatabase,
        int mysqlSlave,
        CompressedDataOutputStream out
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "getTableStatus", AOServPermission.Permission.get_mysql_table_status);
        // Check access
        checkAccessMySQLDatabase(conn, source, "getTableStatus", mysqlDatabase);
        int daemonServer;
        String chrootPath;
        int osv;
        int mysqlServer = getMySQLServerForMySQLDatabase(conn, mysqlDatabase);
        if(mysqlSlave==-1) {
            // Query the master
            daemonServer = getAOServerForMySQLServer(conn, mysqlServer);
            chrootPath = "";
            osv = ServerHandler.getOperatingSystemVersionForServer(conn, daemonServer);
            if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+daemonServer);
        } else {
            // Query the slave
            int slaveMySQLServer = getMySQLServerForFailoverMySQLReplication(conn, mysqlSlave);
            if(slaveMySQLServer!=mysqlServer) throw new SQLException("slaveMySQLServer!=mysqlServer");
            if(conn.executeBooleanQuery("select ao_server is not null from failover_mysql_replications where pkey=?", mysqlSlave)) {
                // ao_server-based
                daemonServer = conn.executeIntQuery("select ao_server from failover_mysql_replications where pkey=?", mysqlSlave);
                chrootPath = "";
                osv = ServerHandler.getOperatingSystemVersionForServer(conn, daemonServer);
                if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+daemonServer);
            } else {
                // replication-based
                daemonServer = conn.executeIntQuery("select bp.ao_server from failover_mysql_replications fmr inner join failover_file_replications ffr on fmr.replication=ffr.pkey inner join backup_partitions bp on ffr.backup_partition=bp.pkey where fmr.pkey=?", mysqlSlave);
                String toPath = conn.executeStringQuery("select bp.path from failover_mysql_replications fmr inner join failover_file_replications ffr on fmr.replication=ffr.pkey inner join backup_partitions bp on ffr.backup_partition=bp.pkey where fmr.pkey=?", mysqlSlave);
                int aoServer = getAOServerForMySQLServer(conn, slaveMySQLServer);
                osv = ServerHandler.getOperatingSystemVersionForServer(conn, aoServer);
                if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+aoServer);
                chrootPath = toPath+"/"+ServerHandler.getHostnameForAOServer(conn, aoServer);
            }
        }
        List<MySQLDatabase.TableStatus> tableStatuses = DaemonHandler.getDaemonConnector(conn, daemonServer).getMySQLTableStatus(
            chrootPath,
            osv,
            getPortForMySQLServer(conn, mysqlServer),
            getMySQLDatabaseName(conn, mysqlDatabase)
        );
        out.writeByte(AOServProtocol.NEXT);
        int size = tableStatuses.size();
        out.writeCompressedInt(size);
        for(int c=0;c<size;c++) {
            MySQLDatabase.TableStatus tableStatus = tableStatuses.get(c);
            out.writeUTF(tableStatus.getName());
            out.writeNullEnum(tableStatus.getEngine());
            out.writeNullInteger(tableStatus.getVersion());
            out.writeNullEnum(tableStatus.getRowFormat());
            out.writeNullLong(tableStatus.getRows());
            out.writeNullLong(tableStatus.getAvgRowLength());
            out.writeNullLong(tableStatus.getDataLength());
            out.writeNullLong(tableStatus.getMaxDataLength());
            out.writeNullLong(tableStatus.getIndexLength());
            out.writeNullLong(tableStatus.getDataFree());
            out.writeNullLong(tableStatus.getAutoIncrement());
            out.writeNullUTF(tableStatus.getCreateTime());
            out.writeNullUTF(tableStatus.getUpdateTime());
            out.writeNullUTF(tableStatus.getCheckTime());
            out.writeNullEnum(tableStatus.getCollation());
            out.writeNullUTF(tableStatus.getChecksum());
            out.writeNullUTF(tableStatus.getCreateOptions());
            out.writeNullUTF(tableStatus.getComment());
        }
    }

    public static void checkTables(
        DatabaseConnection conn,
        RequestSource source,
        int mysqlDatabase,
        int mysqlSlave,
        List<String> tableNames,
        CompressedDataOutputStream out
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "checkTables", AOServPermission.Permission.check_mysql_tables);
        // Check access
        checkAccessMySQLDatabase(conn, source, "checkTables", mysqlDatabase);
        int daemonServer;
        String chrootPath;
        int osv;
        int mysqlServer = getMySQLServerForMySQLDatabase(conn, mysqlDatabase);
        if(mysqlSlave==-1) {
            // Query the master
            daemonServer = getAOServerForMySQLServer(conn, mysqlServer);
            chrootPath = "";
            osv = ServerHandler.getOperatingSystemVersionForServer(conn, daemonServer);
            if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+daemonServer);
        } else {
            // Query the slave
            int slaveMySQLServer = getMySQLServerForFailoverMySQLReplication(conn, mysqlSlave);
            if(slaveMySQLServer!=mysqlServer) throw new SQLException("slaveMySQLServer!=mysqlServer");
            if(conn.executeBooleanQuery("select ao_server is not null from failover_mysql_replications where pkey=?", mysqlSlave)) {
                // ao_server-based
                daemonServer = conn.executeIntQuery("select ao_server from failover_mysql_replications where pkey=?", mysqlSlave);
                chrootPath = "";
                osv = ServerHandler.getOperatingSystemVersionForServer(conn, daemonServer);
                if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+daemonServer);
            } else {
                // replication-based
                daemonServer = conn.executeIntQuery("select bp.ao_server from failover_mysql_replications fmr inner join failover_file_replications ffr on fmr.replication=ffr.pkey inner join backup_partitions bp on ffr.backup_partition=bp.pkey where fmr.pkey=?", mysqlSlave);
                String toPath = conn.executeStringQuery("select bp.path from failover_mysql_replications fmr inner join failover_file_replications ffr on fmr.replication=ffr.pkey inner join backup_partitions bp on ffr.backup_partition=bp.pkey where fmr.pkey=?", mysqlSlave);
                int aoServer = getAOServerForMySQLServer(conn, slaveMySQLServer);
                osv = ServerHandler.getOperatingSystemVersionForServer(conn, aoServer);
                if(osv==-1) throw new SQLException("Unknown operating_system_version for aoServer: "+aoServer);
                chrootPath = toPath+"/"+ServerHandler.getHostnameForAOServer(conn, aoServer);
            }
        }
        List<MySQLDatabase.CheckTableResult> checkTableResults = DaemonHandler.getDaemonConnector(conn, daemonServer).checkMySQLTables(
            chrootPath,
            osv,
            getPortForMySQLServer(conn, mysqlServer),
            getMySQLDatabaseName(conn, mysqlDatabase),
            tableNames
        );
        out.writeByte(AOServProtocol.NEXT);
        int size = checkTableResults.size();
        out.writeCompressedInt(size);
        for(int c=0;c<size;c++) {
            MySQLDatabase.CheckTableResult checkTableResult = checkTableResults.get(c);
            out.writeUTF(checkTableResult.getTable());
            out.writeLong(checkTableResult.getDuration());
            out.writeNullEnum(checkTableResult.getMsgType());
            out.writeNullUTF(checkTableResult.getMsgText());
        }
    }
}
