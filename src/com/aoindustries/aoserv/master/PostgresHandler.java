/*
 * Copyright 2001-2013, 2015, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master;

import com.aoindustries.aoserv.client.account.Account;
import com.aoindustries.aoserv.client.master.Permission;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.password.PasswordChecker;
import com.aoindustries.aoserv.client.postgresql.Database;
import com.aoindustries.aoserv.client.schema.AoservProtocol;
import com.aoindustries.aoserv.client.schema.Table;
import com.aoindustries.aoserv.daemon.client.AOServDaemonConnector;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.util.IntList;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <code>PostgresHandler</code> handles all the accesses to the PostgreSQL tables.
 *
 * @author  AO Industries, Inc.
 */
final public class PostgresHandler {

	private final static Map<Integer,Boolean> disabledPostgresServerUsers=new HashMap<>();
	private final static Map<com.aoindustries.aoserv.client.postgresql.User.Name,Boolean> disabledPostgresUsers=new HashMap<>();

	public static void checkAccessPostgresDatabase(DatabaseConnection conn, RequestSource source, String action, int postgres_database) throws IOException, SQLException {
		User mu = MasterServer.getUser(conn, source.getUsername());
		if(mu!=null) {
			if(MasterServer.getUserHosts(conn, source.getUsername()).length!=0) {
				ServerHandler.checkAccessServer(conn, source, action, getAOServerForPostgresDatabase(conn, postgres_database));
			}
		} else {
			checkAccessPostgresServerUser(conn, source, action, getDatDbaForPostgresDatabase(conn, postgres_database));
		}
	}

	public static void checkAccessPostgresServer(DatabaseConnection conn, RequestSource source, String action, int id) throws IOException, SQLException {
		ServerHandler.checkAccessServer(conn, source, action, getAOServerForPostgresServer(conn, id));
	}

	public static void checkAccessPostgresServerUser(DatabaseConnection conn, RequestSource source, String action, int postgres_server_user) throws IOException, SQLException {
		User mu = MasterServer.getUser(conn, source.getUsername());
		if(mu!=null) {
			if(MasterServer.getUserHosts(conn, source.getUsername()).length!=0) {
				ServerHandler.checkAccessServer(conn, source, action, getAOServerForPostgresServerUser(conn, postgres_server_user));
			}
		} else {
			checkAccessPostgresUser(conn, source, action, getUsernameForPostgresServerUser(conn, postgres_server_user));
		}
	}

	public static void checkAccessPostgresUser(DatabaseConnection conn, RequestSource source, String action, com.aoindustries.aoserv.client.postgresql.User.Name username) throws IOException, SQLException {
		User mu = MasterServer.getUser(conn, source.getUsername());
		if(mu!=null) {
			if(MasterServer.getUserHosts(conn, source.getUsername()).length!=0) {
				IntList psus = getPostgresServerUsersForPostgresUser(conn, username);
				boolean found = false;
				for(int psu : psus) {
					if(ServerHandler.canAccessServer(conn, source, getAOServerForPostgresServerUser(conn, psu))) {
						found=true;
						break;
					}
				}
				if(!found) {
					String message=
						"business_administrator.username="
						+source.getUsername()
						+" is not allowed to access postgres_user: action='"
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

	/**
	 * Adds a PostgreSQL database to the system.
	 */
	public static int addPostgresDatabase(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		Database.Name name,
		int postgresServer,
		int datdba,
		int encoding,
		boolean enable_postgis
	) throws IOException, SQLException {
		if(Database.isSpecial(name)) {
			throw new SQLException("Refusing to add special database: " + name);
		}

		// If requesting PostGIS, make sure the version of PostgreSQL supports it.
		if(
			enable_postgis
			&& conn.executeBooleanQuery("select pv.postgis_version is null from postgresql.\"Server\" ps inner join postgresql.\"Version\" pv on ps.version = pv.version where ps.bind = ?", postgresServer)
		) throw new SQLException("This version of PostgreSQL doesn't support PostGIS");

		// datdba must be on the same server and not be 'mail'
		int datdbaServer=getPostgresServerForPostgresServerUser(conn, datdba);
		if(datdbaServer!=postgresServer) throw new SQLException("(datdba.postgres_server="+datdbaServer+")!=(postgres_server="+postgresServer+")");
		com.aoindustries.aoserv.client.postgresql.User.Name datdbaUsername=getUsernameForPostgresServerUser(conn, datdba);
		if(datdbaUsername.equals(com.aoindustries.aoserv.client.linux.User.MAIL)) throw new SQLException("Not allowed to add Database with datdba of '"+com.aoindustries.aoserv.client.linux.User.MAIL+'\'');
		if(isPostgresServerUserDisabled(conn, datdba)) throw new SQLException("Unable to add Database, UserServer disabled: "+datdba);
		// Look up the accounting code
		Account.Name accounting=UsernameHandler.getBusinessForUsername(conn, datdbaUsername);
		// Encoding must exist for this version of the database
		if(
			!conn.executeBooleanQuery(
				"SELECT EXISTS (\n"
				+ "  SELECT\n"
				+ "    pe.id\n"
				+ "  FROM\n"
				+ "    postgresql.\"Server\" ps\n"
				+ "    INNER JOIN postgresql.\"Encoding\" pe ON ps.version = pe.postgres_version\n"
				+ "  WHERE\n"
				+ "    ps.bind = ?\n"
				+ "    AND pe.id = ?\n"
				+ ")",
				postgresServer,
				encoding
			)
		) throw new SQLException("Server #"+postgresServer+" does not support Encoding #"+encoding);

		// Must be allowed to access this server and package
		int aoServer=getAOServerForPostgresServer(conn, postgresServer);
		ServerHandler.checkAccessServer(conn, source, "addPostgresDatabase", aoServer);
		UsernameHandler.checkAccessUsername(conn, source, "addPostgresDatabase", datdbaUsername);
		// This sub-account must have access to the server
		BusinessHandler.checkBusinessAccessServer(conn, source, "addPostgresDatabase", accounting, aoServer);

		// Add the entry to the database
		int id = conn.executeIntUpdate(
			"INSERT INTO\n"
			+ "  postgresql.\"Database\"\n"
			+ "VALUES (\n"
			+ "  default,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  false,\n"
			+ "  true,\n"
			+ "  ?\n"
			+ ") RETURNING id",
			name,
			postgresServer,
			datdba,
			encoding,
			enable_postgis
		);

		// Notify all clients of the update, the server will detect this change and automatically add the database
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_DATABASES,
			accounting,
			aoServer,
			false
		);
		return id;
	}

	/**
	 * Adds a PostgreSQL server user.
	 */
	public static int addPostgresServerUser(
		DatabaseConnection conn,
		RequestSource source, 
		InvalidateList invalidateList,
		com.aoindustries.aoserv.client.postgresql.User.Name username, 
		int postgresServer
	) throws IOException, SQLException {
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(username)) {
			throw new SQLException("Refusing to add special user: " + username);
		}
		if(username.equals(com.aoindustries.aoserv.client.linux.User.MAIL)) throw new SQLException("Not allowed to add UserServer for user '"+com.aoindustries.aoserv.client.linux.User.MAIL+'\'');

		checkAccessPostgresUser(conn, source, "addPostgresServerUser", username);
		if(isPostgresUserDisabled(conn, username)) throw new SQLException("Unable to add UserServer, User disabled: "+username);
		int aoServer=getAOServerForPostgresServer(conn, postgresServer);
		ServerHandler.checkAccessServer(conn, source, "addPostgresServerUser", aoServer);
		// This sub-account must have access to the server
		UsernameHandler.checkUsernameAccessServer(conn, source, "addPostgresServerUser", username, aoServer);

		int id = conn.executeIntUpdate(
			"INSERT INTO postgresql.\"UserServer\" VALUES (default,?,?,null,null) RETURNING id",
			username,
			postgresServer
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_SERVER_USERS,
			UsernameHandler.getBusinessForUsername(conn, username),
			aoServer,
			true
		);
		return id;
	}

	/**
	 * Adds a PostgreSQL user.
	 */
	public static void addPostgresUser(
		DatabaseConnection conn,
		RequestSource source, 
		InvalidateList invalidateList,
		com.aoindustries.aoserv.client.postgresql.User.Name username
	) throws IOException, SQLException {
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(username)) {
			throw new SQLException("Refusing to add special user: " + username);
		}
		if(username.equals(com.aoindustries.aoserv.client.linux.User.MAIL)) throw new SQLException("Not allowed to add User for user '"+com.aoindustries.aoserv.client.linux.User.MAIL+'\'');

		UsernameHandler.checkAccessUsername(conn, source, "addPostgresUser", username);
		if(UsernameHandler.isUsernameDisabled(conn, username)) throw new SQLException("Unable to add User, Username disabled: "+username);

		conn.executeUpdate(
			"insert into postgresql.\"User\"(username) values(?)",
			username
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_USERS,
			UsernameHandler.getBusinessForUsername(conn, username),
			InvalidateList.allServers,
			false
		);
	}

	public static void disablePostgresServerUser(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int disableLog,
		int id
	) throws IOException, SQLException {
		BusinessHandler.checkAccessDisableLog(conn, source, "disablePostgresServerUser", disableLog, false);
		checkAccessPostgresServerUser(conn, source, "disablePostgresServerUser", id);

		com.aoindustries.aoserv.client.postgresql.User.Name pu = getUsernameForPostgresServerUser(conn, id);
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(pu)) {
			throw new SQLException("Refusing to disable special user: " + pu);
		}
		if(isPostgresServerUserDisabled(conn, id)) throw new SQLException("UserServer is already disabled: "+id);

		conn.executeUpdate(
			"update postgresql.\"UserServer\" set disable_log=? where id=?",
			disableLog,
			id
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_SERVER_USERS,
			getBusinessForPostgresServerUser(conn, id),
			getAOServerForPostgresServerUser(conn, id),
			false
		);
	}

	public static void disablePostgresUser(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int disableLog,
		com.aoindustries.aoserv.client.postgresql.User.Name username
	) throws IOException, SQLException {
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(username)) {
			throw new SQLException("Refusing to disable special user: " + username);
		}

		BusinessHandler.checkAccessDisableLog(conn, source, "disablePostgresUser", disableLog, false);
		checkAccessPostgresUser(conn, source, "disablePostgresUser", username);

		if(isPostgresUserDisabled(conn, username)) throw new SQLException("User is already disabled: "+username);

		IntList psus=getPostgresServerUsersForPostgresUser(conn, username);
		for(int c=0;c<psus.size();c++) {
			int psu=psus.getInt(c);
			if(!isPostgresServerUserDisabled(conn, psu)) {
				throw new SQLException("Cannot disable User '"+username+"': UserServer not disabled: "+psu);
			}
		}

		conn.executeUpdate(
			"update postgresql.\"User\" set disable_log=? where username=?",
			disableLog,
			username
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_USERS,
			UsernameHandler.getBusinessForUsername(conn, username),
			UsernameHandler.getServersForUsername(conn, username),
			false
		);
	}

	/**
	 * Dumps a PostgreSQL database
	 */
	public static void dumpPostgresDatabase(
		DatabaseConnection conn,
		RequestSource source,
		CompressedDataOutputStream out,
		int dbPKey,
		boolean gzip
	) throws IOException, SQLException {
		checkAccessPostgresDatabase(conn, source, "dumpPostgresDatabase", dbPKey);

		int aoServer=getAOServerForPostgresDatabase(conn, dbPKey);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.dumpPostgresDatabase(
			dbPKey,
			gzip,
			(long dumpSize) -> {
				if(source.getProtocolVersion().compareTo(AoservProtocol.Version.VERSION_1_80_0) >= 0) {
					out.writeLong(dumpSize);
				}
			},
			out
		);
	}

	public static void enablePostgresServerUser(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		checkAccessPostgresServerUser(conn, source, "enablePostgresServerUser", id);
		int disableLog=getDisableLogForPostgresServerUser(conn, id);
		if(disableLog==-1) throw new SQLException("UserServer is already enabled: "+id);
		BusinessHandler.checkAccessDisableLog(conn, source, "enablePostgresServerUser", disableLog, true);

		com.aoindustries.aoserv.client.postgresql.User.Name pu = getUsernameForPostgresServerUser(conn, id);
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(pu)) {
			throw new SQLException("Refusing to enable special user: " + pu);
		}
		if(isPostgresUserDisabled(conn, pu)) throw new SQLException("Unable to enable UserServer #"+id+", User not enabled: "+pu);

		conn.executeUpdate(
			"update postgresql.\"UserServer\" set disable_log=null where id=?",
			id
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_SERVER_USERS,
			UsernameHandler.getBusinessForUsername(conn, pu),
			getAOServerForPostgresServerUser(conn, id),
			false
		);
	}

	public static void enablePostgresUser(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		com.aoindustries.aoserv.client.postgresql.User.Name username
	) throws IOException, SQLException {
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(username)) {
			throw new SQLException("Refusing to enable special user: " + username);
		}

		UsernameHandler.checkAccessUsername(conn, source, "enablePostgresUser", username);
		int disableLog=getDisableLogForPostgresUser(conn, username);
		if(disableLog==-1) throw new SQLException("User is already enabled: "+username);
		BusinessHandler.checkAccessDisableLog(conn, source, "enablePostgresUser", disableLog, true);

		if(UsernameHandler.isUsernameDisabled(conn, username)) throw new SQLException("Unable to enable User '"+username+"', Username not enabled: "+username);

		conn.executeUpdate(
			"update postgresql.\"User\" set disable_log=null where username=?",
			username
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_USERS,
			UsernameHandler.getBusinessForUsername(conn, username),
			UsernameHandler.getServersForUsername(conn, username),
			false
		);
	}

	/**
	 * Generates a unique PostgreSQL database name.
	 */
	public static Database.Name generatePostgresDatabaseName(
		DatabaseConnection conn,
		String template_base,
		String template_added
	) throws IOException, SQLException {
		// Load the entire list of postgres database names
		Set<Database.Name> names = conn.executeObjectCollectionQuery(new HashSet<>(),
			ObjectFactories.postgresqlDatabaseNameFactory,
			"select name from postgresql.\"Database\" group by name"
		);
		// Find one that is not used
		for(int c=0;c<Integer.MAX_VALUE;c++) {
			Database.Name name;
			try {
				name = Database.Name.valueOf((c==0) ? template_base : (template_base+template_added+c));
			} catch(ValidationException e) {
				throw new SQLException(e.getLocalizedMessage(), e);
			}
			if(!names.contains(name)) return name;
		}
		// If could not find one, report and error
		throw new SQLException("Unable to find available PostgreSQL database name for template_base="+template_base+" and template_added="+template_added);
	}

	public static int getDisableLogForPostgresServerUser(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeIntQuery("select coalesce(disable_log, -1) from postgresql.\"UserServer\" where id=?", id);
	}

	public static int getDisableLogForPostgresUser(DatabaseConnection conn, com.aoindustries.aoserv.client.postgresql.User.Name username) throws IOException, SQLException {
		return conn.executeIntQuery("select coalesce(disable_log, -1) from postgresql.\"User\" where username=?", username);
	}

	public static Database.Name getNameForPostgresDatabase(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeObjectQuery(
			ObjectFactories.postgresqlDatabaseNameFactory,
			"select \"name\" from postgresql.\"Database\" where id=?",
			id
		);
	}

	public static IntList getPostgresServerUsersForPostgresUser(DatabaseConnection conn, com.aoindustries.aoserv.client.postgresql.User.Name username) throws IOException, SQLException {
		return conn.executeIntListQuery("select id from postgresql.\"UserServer\" where username=?", username);
	}

	public static com.aoindustries.aoserv.client.postgresql.User.Name getUsernameForPostgresServerUser(DatabaseConnection conn, int psu) throws IOException, SQLException {
		return conn.executeObjectQuery(
			ObjectFactories.postgresqlUserNameFactory,
			"select username from postgresql.\"UserServer\" where id=?",
			psu
		);
	}

	public static void invalidateTable(Table.TableID tableID) {
		switch(tableID) {
			case POSTGRES_SERVER_USERS :
				synchronized(PostgresHandler.class) {
					disabledPostgresServerUsers.clear();
				}
				break;
			case POSTGRES_USERS :
				synchronized(PostgresHandler.class) {
					disabledPostgresUsers.clear();
				}
				break;
		}
	}

	public static boolean isPostgresServerUserDisabled(DatabaseConnection conn, int id) throws IOException, SQLException {
		synchronized(PostgresHandler.class) {
			Integer I = id;
			Boolean O=disabledPostgresServerUsers.get(I);
			if(O!=null) return O;
			boolean isDisabled=getDisableLogForPostgresServerUser(conn, id)!=-1;
			disabledPostgresServerUsers.put(I, isDisabled);
			return isDisabled;
		}
	}

	public static boolean isPostgresUser(DatabaseConnection conn, com.aoindustries.aoserv.client.postgresql.User.Name username) throws IOException, SQLException {
		return conn.executeBooleanQuery(
			"select\n"
			+ "  (\n"
			+ "    select\n"
			+ "      username\n"
			+ "    from\n"
			+ "      postgresql.\"User\"\n"
			+ "    where\n"
			+ "      username=?\n"
			+ "    limit 1\n"
			+ "  ) is not null",
			username
		);
	}

	public static boolean isPostgresUserDisabled(DatabaseConnection conn, com.aoindustries.aoserv.client.postgresql.User.Name username) throws IOException, SQLException {
		synchronized(PostgresHandler.class) {
			Boolean O=disabledPostgresUsers.get(username);
			if(O!=null) return O;
			boolean isDisabled=getDisableLogForPostgresUser(conn, username)!=-1;
			disabledPostgresUsers.put(username, isDisabled);
			return isDisabled;
		}
	}

	/**
	 * Determines if a PostgreSQL database name is available.
	 */
	public static boolean isPostgresDatabaseNameAvailable(
		DatabaseConnection conn,
		RequestSource source,
		Database.Name name,
		int postgresServer
	) throws IOException, SQLException {
		int aoServer=getAOServerForPostgresServer(conn, postgresServer);
		ServerHandler.checkAccessServer(
			conn,
			source,
			"isPostgresDatabaseNameAvailable",
			aoServer
		);
		return conn.executeBooleanQuery(
			"select\n"
			+ "  (\n"
			+ "    select\n"
			+ "      id\n"
			+ "    from\n"
			+ "      postgresql.\"Database\"\n"
			+ "    where\n"
			+ "      name=?\n"
			+ "      and postgres_server=?\n"
			+ "    limit 1\n"
			+ "  ) is null",
			name,
			postgresServer
		);
	}

	/**
	 * Determines if a PostgreSQL server name is available.
	 */
	public static boolean isPostgresServerNameAvailable(
		DatabaseConnection conn,
		RequestSource source,
		com.aoindustries.aoserv.client.postgresql.Server.Name name,
		int aoServer
	) throws IOException, SQLException {
		ServerHandler.checkAccessServer(conn, source, "isPostgresServerNameAvailable", aoServer);
		return conn.executeBooleanQuery("SELECT NOT EXISTS (SELECT * FROM postgresql.\"Server\" WHERE \"name\" = ? AND ao_server = ?)", name, aoServer);
	}

	public static boolean isPostgresServerUserPasswordSet(
		DatabaseConnection conn,
		RequestSource source, 
		int psu
	) throws IOException, SQLException {
		checkAccessPostgresServerUser(conn, source, "isPostgresServerUserPasswordSet", psu);
		com.aoindustries.aoserv.client.postgresql.User.Name username = getUsernameForPostgresServerUser(conn, psu);
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(username)) throw new SQLException("Refusing to check if passwords set on special user: " + username);
		if(isPostgresServerUserDisabled(conn, psu)) throw new SQLException("Unable to determine if UserServer password is set, account disabled: " + psu);

		int aoServer = getAOServerForPostgresServerUser(conn, psu);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		String password = daemonConnector.getPostgresUserPassword(psu);
		return !com.aoindustries.aoserv.client.postgresql.User.NO_PASSWORD_DB_VALUE.equals(password);
	}

	/**
	 * Removes a Database from the system.
	 */
	public static void removePostgresDatabase(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		checkAccessPostgresDatabase(conn, source, "removePostgresDatabase", id);

		removePostgresDatabase(conn, invalidateList, id);
	}

	/**
	 * Removes a Database from the system.
	 */
	public static void removePostgresDatabase(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		Database.Name name = getNameForPostgresDatabase(conn, id);
		if(Database.isSpecial(name)) {
			throw new SQLException("Refusing to remove special database: " + name);
		}
		// Remove the database entry
		Account.Name accounting = getBusinessForPostgresDatabase(conn, id);
		int aoServer=getAOServerForPostgresDatabase(conn, id);
		conn.executeUpdate("delete from postgresql.\"Database\" where id=?", id);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_DATABASES,
			accounting,
			aoServer,
			false
		);
	}

	/**
	 * Removes a UserServer from the system.
	 */
	public static void removePostgresServerUser(
		DatabaseConnection conn,
		RequestSource source, 
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		checkAccessPostgresServerUser(conn, source, "removePostgresServerUser", id);

		com.aoindustries.aoserv.client.postgresql.User.Name pu = getUsernameForPostgresServerUser(conn, id);
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(pu)) {
			throw new SQLException("Refusing to remove special user: " + pu);
		}

		// Get the details for later use
		int aoServer=getAOServerForPostgresServerUser(conn, id);
		Account.Name accounting = getBusinessForPostgresServerUser(conn, id);

		// Make sure that this is not the DBA for any databases
		int count=conn.executeIntQuery("select count(*) from postgresql.\"Database\" where datdba=?", id);
		if(count>0) throw new SQLException("UserServer #"+id+" cannot be removed because it is the datdba for "+count+(count==1?" database":" databases"));

		// Remove the postgres_server_user
		conn.executeUpdate("delete from postgresql.\"UserServer\" where id=?", id);

		// Notify all clients of the updates
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_SERVER_USERS,
			accounting,
			aoServer,
			true
		);
	}

	/**
	 * Removes a User from the system.
	 */
	public static void removePostgresUser(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		com.aoindustries.aoserv.client.postgresql.User.Name username
	) throws IOException, SQLException {
		checkAccessPostgresUser(conn, source, "removePostgresUser", username);

		removePostgresUser(conn, invalidateList, username);
	}

	/**
	 * Removes a User from the system.
	 */
	public static void removePostgresUser(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		com.aoindustries.aoserv.client.postgresql.User.Name username
	) throws IOException, SQLException {
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(username)) {
			throw new SQLException("Refusing to remove special user: " + username);
		}
		Account.Name accounting = UsernameHandler.getBusinessForUsername(conn, username);

		// Remove the postgres_server_user
		IntList aoServers=conn.executeIntListQuery("select ps.ao_server from postgresql.\"UserServer\" psu, postgresql.\"Server\" ps where psu.username=? and psu.postgres_server = ps.bind", username);
		if(aoServers.size()>0) {
			conn.executeUpdate("delete from postgresql.\"UserServer\" where username=?", username);
			invalidateList.addTable(
				conn,
				Table.TableID.POSTGRES_SERVER_USERS,
				accounting,
				aoServers,
				false
			);
		}

		// Remove the postgres_user
		conn.executeUpdate("delete from postgresql.\"User\" where username=?", username);
		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_USERS,
			accounting,
			BusinessHandler.getServersForBusiness(conn, accounting),
			false
		);
	}

	/**
	 * Sets a PostgreSQL password.
	 */
	public static void setPostgresServerUserPassword(
		DatabaseConnection conn,
		RequestSource source,
		int postgres_server_user,
		String password
	) throws IOException, SQLException {
		BusinessHandler.checkPermission(conn, source, "setPostgresServerUserPassword", Permission.Name.set_postgres_server_user_password);
		checkAccessPostgresServerUser(conn, source, "setPostgresServerUserPassword", postgres_server_user);
		if(isPostgresServerUserDisabled(conn, postgres_server_user)) throw new SQLException("Unable to set UserServer password, account disabled: "+postgres_server_user);

		com.aoindustries.aoserv.client.postgresql.User.Name pu = getUsernameForPostgresServerUser(conn, postgres_server_user);
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(pu)) {
			throw new SQLException("Refusing to set the password for a special user: " + pu);
		}

		// Get the server for the user
		int aoServer=getAOServerForPostgresServerUser(conn, postgres_server_user);

		// Perform the password check here, too.
		if(password!=com.aoindustries.aoserv.client.postgresql.User.NO_PASSWORD) {
			List<PasswordChecker.Result> results = com.aoindustries.aoserv.client.postgresql.User.checkPassword(pu, password);
			if(PasswordChecker.hasResults(results)) throw new SQLException("Invalid password: "+PasswordChecker.getResultsString(results).replace('\n', '|'));
		}

		// Contact the daemon for the update
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.setPostgresUserPassword(postgres_server_user, password);
	}

	public static void setPostgresServerUserPredisablePassword(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int psu,
		String password
	) throws IOException, SQLException {
		checkAccessPostgresServerUser(conn, source, "setPostgresServerUserPredisablePassword", psu);

		com.aoindustries.aoserv.client.postgresql.User.Name pu = getUsernameForPostgresServerUser(conn, psu);
		if(com.aoindustries.aoserv.client.postgresql.User.isSpecial(pu)) {
			throw new SQLException("May not disable special PostgreSQL user: " + pu);
		}

		if(password==null) {
			if(isPostgresServerUserDisabled(conn, psu)) throw new SQLException("Unable to clear UserServer predisable password, account disabled: "+psu);
		} else {
			if(!isPostgresServerUserDisabled(conn, psu)) throw new SQLException("Unable to set UserServer predisable password, account not disabled: "+psu);
		}

		// Update the database
		conn.executeUpdate(
			"update postgresql.\"UserServer\" set predisable_password=? where id=?",
			password,
			psu
		);

		invalidateList.addTable(
			conn,
			Table.TableID.POSTGRES_SERVER_USERS,
			getBusinessForPostgresServerUser(conn, psu),
			getAOServerForPostgresServerUser(conn, psu),
			false
		);
	}

	public static void waitForPostgresDatabaseRebuild(
		DatabaseConnection conn,
		RequestSource source,
		int aoServer
	) throws IOException, SQLException {
		ServerHandler.checkAccessServer(conn, source, "waitForPostgresDatabaseRebuild", aoServer);
		ServerHandler.waitForInvalidates(aoServer);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.waitForPostgresDatabaseRebuild();
	}

	public static void waitForPostgresServerRebuild(
		DatabaseConnection conn,
		RequestSource source,
		int aoServer
	) throws IOException, SQLException {
		ServerHandler.checkAccessServer(conn, source, "waitForPostgresServerRebuild", aoServer);
		ServerHandler.waitForInvalidates(aoServer);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.waitForPostgresServerRebuild();
	}

	public static void waitForPostgresUserRebuild(
		DatabaseConnection conn,
		RequestSource source,
		int aoServer
	) throws IOException, SQLException {
		ServerHandler.checkAccessServer(conn, source, "waitForPostgresUserRebuild", aoServer);
		ServerHandler.waitForInvalidates(aoServer);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.waitForPostgresUserRebuild();
	}

	public static Account.Name getBusinessForPostgresDatabase(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeObjectQuery(ObjectFactories.accountNameFactory,
			"select\n"
			+ "  pk.accounting\n"
			+ "from\n"
			+ "  postgresql.\"Database\" pd,\n"
			+ "  postgresql.\"UserServer\" psu,\n"
			+ "  account.\"User\" un,\n"
			+ "  billing.\"Package\" pk\n"
			+ "where\n"
			+ "  pd.id=?\n"
			+ "  and pd.datdba=psu.id\n"
			+ "  and psu.username=un.username\n"
			+ "  and un.package=pk.name",
			id
		);
	}

	public static int getPackageForPostgresDatabase(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeIntQuery(
			"select\n"
			+ "  pk.id\n"
			+ "from\n"
			+ "  postgresql.\"Database\" pd,\n"
			+ "  postgresql.\"UserServer\" psu,\n"
			+ "  account.\"User\" un,\n"
			+ "  billing.\"Package\" pk\n"
			+ "where\n"
			+ "  pd.id=?\n"
			+ "  and pd.datdba=psu.id\n"
			+ "  and psu.username=un.username\n"
			+ "  and un.package=pk.name",
			id
		);
	}

	public static Account.Name getBusinessForPostgresServerUser(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeObjectQuery(ObjectFactories.accountNameFactory,
			"select pk.accounting from postgresql.\"UserServer\" psu, account.\"User\" un, billing.\"Package\" pk where psu.username=un.username and un.package=pk.name and psu.id=?",
			id
		);
	}

	public static int getAOServerForPostgresServer(DatabaseConnection conn, int postgresServer) throws IOException, SQLException {
		return conn.executeIntQuery("select ao_server from postgresql.\"Server\" where bind = ?", postgresServer);
	}

	public static int getPortForPostgresServer(DatabaseConnection conn, int postgresServer) throws IOException, SQLException {
		return conn.executeIntQuery("select nb.port from postgresql.\"Server\" ps, net.\"Bind\" nb where ps.bind = ? and ps.bind = nb.id", postgresServer);
	}

	public static String getMinorVersionForPostgresServer(DatabaseConnection conn, int postgresServer) throws IOException, SQLException {
		return conn.executeStringQuery(
			"SELECT\n"
			+ "  pv.minor_version\n"
			+ "FROM\n"
			+ "  postgresql.\"Server\" ps\n"
			+ "  INNER JOIN postgresql.\"Version\" pv ON ps.version = pv.version\n"
			+ "WHERE\n"
			+ "  ps.bind = ?",
			postgresServer
		);
	}

	public static int getPostgresServerForPostgresDatabase(DatabaseConnection conn, int postgresDatabase) throws IOException, SQLException {
		return conn.executeIntQuery(
			"select postgres_server from postgresql.\"Database\" where id=?",
			postgresDatabase
		);
	}

	public static int getPostgresServerForPostgresServerUser(DatabaseConnection conn, int postgres_server_user) throws IOException, SQLException {
		return conn.executeIntQuery("select postgres_server from postgresql.\"UserServer\" where id=?", postgres_server_user);
	}

	public static int getAOServerForPostgresDatabase(DatabaseConnection conn, int postgresDatabase) throws IOException, SQLException {
		return conn.executeIntQuery(
			"SELECT\n"
			+ "  ps.ao_server\n"
			+ "FROM\n"
			+ "  postgresql.\"Database\" pd\n"
			+ "  INNER JOIN postgresql.\"Server\" ps ON pd.postgres_server = ps.bind\n"
			+ "WHERE\n"
			+ "  pd.id=?",
			postgresDatabase
		);
	}

	public static int getDatDbaForPostgresDatabase(DatabaseConnection conn, int postgresDatabase) throws IOException, SQLException {
		return conn.executeIntQuery(
			"select\n"
			+ "  datdba\n"
			+ "from\n"
			+ "  postgresql.\"Database\"\n"
			+ "where\n"
			+ "  id=?",
			postgresDatabase
		);
	}

	public static int getAOServerForPostgresServerUser(DatabaseConnection conn, int postgres_server_user) throws IOException, SQLException {
		return conn.executeIntQuery(
			"SELECT\n"
			+ "  ps.ao_server\n"
			+ "FROM\n"
			+ "  postgresql.\"UserServer\" psu\n"
			+ "  INNER JOIN postgresql.\"Server\" ps ON psu.postgres_server = ps.bind\n"
			+ "WHERE\n"
			+ "  psu.id=?",
			postgres_server_user
		);
	}

	public static void restartPostgreSQL(
		DatabaseConnection conn,
		RequestSource source,
		int postgresServer
	) throws IOException, SQLException {
		int aoServer=getAOServerForPostgresServer(conn, postgresServer);
		boolean canControl=BusinessHandler.canBusinessServer(conn, source, aoServer, "can_control_postgresql");
		if(!canControl) throw new SQLException("Not allowed to restart PostgreSQL on "+aoServer);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.restartPostgres(postgresServer);
	}

	public static void startPostgreSQL(
		DatabaseConnection conn,
		RequestSource source,
		int postgresServer
	) throws IOException, SQLException {
		int aoServer=getAOServerForPostgresServer(conn, postgresServer);
		boolean canControl=BusinessHandler.canBusinessServer(conn, source, aoServer, "can_control_postgresql");
		if(!canControl) throw new SQLException("Not allowed to start PostgreSQL on "+aoServer);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.startPostgreSQL(postgresServer);
	}

	public static void stopPostgreSQL(
		DatabaseConnection conn,
		RequestSource source,
		int postgresServer
	) throws IOException, SQLException {
		int aoServer=getAOServerForPostgresServer(conn, postgresServer);
		boolean canControl=BusinessHandler.canBusinessServer(conn, source, aoServer, "can_control_postgresql");
		if(!canControl) throw new SQLException("Not allowed to stop PostgreSQL on "+aoServer);
		AOServDaemonConnector daemonConnector = DaemonHandler.getDaemonConnector(conn, aoServer);
		conn.releaseConnection();
		daemonConnector.stopPostgreSQL(postgresServer);
	}

	private PostgresHandler() {}
}
