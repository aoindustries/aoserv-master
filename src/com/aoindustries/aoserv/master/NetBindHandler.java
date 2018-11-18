/*
 * Copyright 2001-2013, 2014, 2015, 2016, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master;

import com.aoindustries.aoserv.client.AOServProtocol;
import com.aoindustries.aoserv.client.FirewalldZone;
import com.aoindustries.aoserv.client.HttpdWorker;
import com.aoindustries.aoserv.client.IPAddress;
import com.aoindustries.aoserv.client.MasterUser;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.client.validator.AccountingCode;
import com.aoindustries.aoserv.client.validator.FirewalldZoneName;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.net.InetAddress;
import com.aoindustries.net.Port;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The <code>NetBindHandler</code> handles all the accesses to the <code>net_binds</code> table.
 *
 * @author  AO Industries, Inc.
 */
final public class NetBindHandler {

	private NetBindHandler() {
	}

	/**
	 * This lock is used to avoid a race condition between check and insert when allocating net_binds.
	 */
	private static final Object netBindLock = new Object();

	public static int addNetBind(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int server,
		AccountingCode packageName,
		int ipAddress,
		Port port,
		String appProtocol,
		boolean monitoringEnabled,
		Set<FirewalldZoneName> firewalldZones
	) throws IOException, SQLException {
		if(
			conn.executeBooleanQuery("select (select protocol from protocols where protocol=?) is null", appProtocol)
		) throw new SQLException("Unable to find in table protocols: "+appProtocol);

		MasterUser mu=MasterServer.getMasterUser(conn, source.getUsername());
		if(mu==null) {
			// Must be a user service
			if(
				!conn.executeBooleanQuery("select is_user_service from protocols where protocol=?", appProtocol)
			) throw new SQLException("Only master users may add non-user net_binds.");

			// Must match the default port
			Port defaultPort = conn.executeObjectQuery(
				ObjectFactories.portFactory,
				"select port, net_protocol from protocols where protocol=?",
				appProtocol
			);
			if(port != defaultPort) throw new SQLException("Only master users may override the port for a service.");
		}

		ServerHandler.checkAccessServer(conn, source, "addNetBind", server);
		PackageHandler.checkAccessPackage(conn, source, "addNetBind", packageName);
		if(PackageHandler.isPackageDisabled(conn, packageName)) throw new SQLException("Unable to add net bind, package disabled: "+packageName);
		IPAddressHandler.checkAccessIPAddress(conn, source, "addNetBind", ipAddress);
		InetAddress inetAddress = IPAddressHandler.getInetAddressForIPAddress(conn, ipAddress);

		int pkey;
		synchronized(netBindLock) {
			if(inetAddress.isUnspecified()) {
				// Wildcard must be unique per server
				if(
					conn.executeBooleanQuery(
						"select\n"
						+ "  (\n"
						+ "    select\n"
						+ "      pkey\n"
						+ "    from\n"
						+ "      net_binds\n"
						+ "    where\n"
						+ "      server=?\n"
						+ "      and port=?\n"
						+ "      and net_protocol=?\n"
						+ "    limit 1\n"
						+ "  ) is not null",
						server,
						port.getPort(),
						port.getProtocol().name().toLowerCase(Locale.ROOT)
					)
				) throw new SQLException("NetBind already in use: "+server+"->"+inetAddress.toBracketedString()+":"+port);
			} else if(inetAddress.isLoopback()) {
				// Loopback must be unique per server and not have wildcard
				if(
					conn.executeBooleanQuery(
						"select\n"
						+ "  (\n"
						+ "    select\n"
						+ "      nb.pkey\n"
						+ "    from\n"
						+ "      net_binds nb\n"
						+ "      inner join \"IPAddress\" ia on nb.\"ipAddress\"=ia.id\n"
						+ "    where\n"
						+ "      nb.server=?\n"
						+ "      and ia.ip_address in (?,?)\n"
						+ "      and nb.port=?\n"
						+ "      and nb.net_protocol=?\n"
						+ "    limit 1\n"
						+ "  ) is not null",
						server,
						IPAddress.WILDCARD_IP,
						IPAddress.LOOPBACK_IP,
						port.getPort(),
						port.getProtocol().name().toLowerCase(Locale.ROOT)
					)
				) throw new SQLException("NetBind already in use: "+server+"->"+inetAddress.toBracketedString()+":"+port);
			} else {
				// Make sure that this port is not already allocated within the server on this IP or the wildcard
				if(
					conn.executeBooleanQuery(
						"select\n"
						+ "  (\n"
						+ "    select\n"
						+ "      nb.pkey\n"
						+ "    from\n"
						+ "      net_binds nb\n"
						+ "      inner join \"IPAddress\" ia on nb.\"ipAddress\"=ia.id\n"
						+ "    where\n"
						+ "      nb.server=?\n"
						+ "      and (\n"
						+ "        ia.ip_address=?\n"
						+ "        or nb.\"ipAddress\"=?\n"
						+ "      )\n"
						+ "      and nb.port=?\n"
						+ "      and nb.net_protocol=?\n"
						+ "    limit 1\n"
						+ "  ) is not null",
						server,
						IPAddress.WILDCARD_IP,
						ipAddress,
						port.getPort(),
						port.getProtocol().name().toLowerCase(Locale.ROOT)
					)
				) throw new SQLException("NetBind already in use: "+server+"->"+inetAddress.toBracketedString()+":"+port);
			}

			// Add the port to the DB
			pkey = conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('net_binds_pkey_seq')");
			conn.executeUpdate(
				"insert into\n"
				+ "  net_binds\n"
				+ "values(\n"
				+ "  ?,\n"
				+ "  ?,\n"
				+ "  ?,\n"
				+ "  ?,\n"
				+ "  ?,\n"
				+ "  ?,\n"
				+ "  ?,\n"
				+ "  ?\n"
				+ ")",
				pkey,
				packageName,
				server,
				ipAddress,
				port.getPort(),
				port.getProtocol().name().toLowerCase(Locale.ROOT),
				appProtocol,
				monitoringEnabled
			);
		}
		AccountingCode business = PackageHandler.getBusinessForPackage(conn, packageName);
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.NET_BINDS,
			business,
			server,
			false
		);
		if(!firewalldZones.isEmpty()) {
			for(FirewalldZoneName firewalldZone : firewalldZones) {
				conn.executeUpdate(
					"insert into net_bind_firewalld_zones (net_bind, firewalld_zone) values (\n"
					+ "  ?,\n"
					+ "  (select pkey from firewalld_zones where server=? and \"name\"=?)\n"
					+ ")",
					pkey,
					server,
					firewalldZone
				);
			}
			invalidateList.addTable(
				conn,
				SchemaTable.TableID.NET_BIND_FIREWALLD_ZONES,
				business,
				server,
				false
			);
		}
		return pkey;
	}

	public static int allocateNetBind(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		int server,
		int ipAddress,
		com.aoindustries.net.Protocol netProtocol,
		String appProtocol,
		AccountingCode pack,
		int minimumPort
	) throws IOException, SQLException {
		InetAddress inetAddress = IPAddressHandler.getInetAddressForIPAddress(conn, ipAddress);
		int pkey;
		synchronized(netBindLock) {
			pkey = conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('net_binds_pkey_seq')");
			if(inetAddress.isUnspecified()) {
				conn.executeUpdate(
					"insert into\n"
					+ "  net_binds\n"
					+ "values(\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  (\n"
					+ "    select\n"
					+ "      np.port\n"
					+ "    from\n"
					+ "      net_ports np\n"
					+ "    where\n"
					+ "      np.is_user\n"
					+ "      and np.port != ?\n"
					+ "      and np.port >= ?\n"
					+ "      and (\n"
					+ "        select\n"
					+ "          nb.pkey\n"
					+ "        from\n"
					+ "          net_binds nb\n"
					+ "        where\n"
					+ "          nb.server=?\n"
					+ "          and np.port=nb.port\n"
					+ "          and nb.net_protocol=?\n"
					+ "        limit 1\n"
					+ "      ) is null\n"
					+ "    order by\n"
					+ "      port\n"
					+ "    limit 1\n"
					+ "  ),\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  true,\n"
					+ "  null\n"
					+ ")",
					pkey,
					pack,
					server,
					ipAddress,
					HttpdWorker.ERROR_CAUSING_PORT,
					minimumPort,
					server,
					netProtocol.name().toLowerCase(Locale.ROOT),
					netProtocol.name().toLowerCase(Locale.ROOT),
					appProtocol
				);
			} else {
				conn.executeUpdate(
					"insert into\n"
					+ "  net_binds\n"
					+ "values(\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  (\n"
					+ "    select\n"
					+ "      np.port\n"
					+ "    from\n"
					+ "      net_ports np\n"
					+ "    where\n"
					+ "      np.is_user\n"
					+ "      and np.port != ?\n"
					+ "      and np.port >= ?\n"
					+ "      and (\n"
					+ "        select\n"
					+ "          nb.pkey\n"
					+ "        from\n"
					+ "          net_binds nb\n"
					+ "          inner join \"IPAddress\" ia on nb.\"ipAddress\"=ia.id\n"
					+ "        where\n"
					+ "          nb.server=?\n"
					+ "          and ia.ip_address in (\n"
					+ "            (select ip_address from \"IPAddress\" where id=?),\n"
					+ "            ?\n"
					+ "          )\n"
					+ "          and np.port=nb.port\n"
					+ "          and nb.net_protocol=?\n"
					+ "        limit 1\n"
					+ "      ) is null\n"
					+ "    order by\n"
					+ "      port\n"
					+ "    limit 1\n"
					+ "  ),\n"
					+ "  ?,\n"
					+ "  ?,\n"
					+ "  true,\n"
					+ "  null\n"
					+ ")",
					pkey,
					pack,
					server,
					ipAddress,
					HttpdWorker.ERROR_CAUSING_PORT,
					minimumPort,
					server,
					ipAddress,
					IPAddress.WILDCARD_IP,
					netProtocol.name().toLowerCase(Locale.ROOT),
					netProtocol.name().toLowerCase(Locale.ROOT),
					appProtocol
				);
			}
		}
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.NET_BINDS,
			PackageHandler.getBusinessForPackage(conn, pack),
			server,
			false
		);
		return pkey;
	}

	public static AccountingCode getBusinessForNetBind(
		DatabaseConnection conn,
		int pkey
	) throws IOException, SQLException {
		return conn.executeObjectQuery(
			ObjectFactories.accountingCodeFactory,
			"select pk.accounting from net_binds nb, packages pk where nb.pkey=? and nb.package=pk.name",
			pkey
		);
	}

	public static int getNetBind(
		DatabaseConnection conn,
		int server,
		int ipAddress,
		Port port
	) throws IOException, SQLException {
		return conn.executeIntQuery(
			"select\n"
			+ "  coalesce(\n"
			+ "    (\n"
			+ "      select\n"
			+ "        pkey\n"
			+ "      from\n"
			+ "        net_binds\n"
			+ "      where\n"
			+ "        server=?\n"
			+ "        and \"ipAddress\"=?\n"
			+ "        and port=?\n"
			+ "        and net_protocol=?\n"
			+ "    ), -1\n"
			+ "  )",
			server,
			ipAddress,
			port.getPort(),
			port.getProtocol().name().toLowerCase(Locale.ROOT)
		);
	}

	public static int getServerForNetBind(
		DatabaseConnection conn,
		int pkey
	) throws IOException, SQLException {
		return conn.executeIntQuery("select server from net_binds where pkey=?", pkey);
	}

	public static AccountingCode getPackageForNetBind(
		DatabaseConnection conn,
		int pkey
	) throws IOException, SQLException {
		return conn.executeObjectQuery(
			ObjectFactories.accountingCodeFactory,
			"select package from net_binds where pkey=?",
			pkey
		);
	}

	public static void removeNetBind(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int pkey
	) throws IOException, SQLException {
		// Security checks
		PackageHandler.checkAccessPackage(conn, source, "removeNetBind", getPackageForNetBind(conn, pkey));

		// Do the remove
		removeNetBind(conn, invalidateList, pkey);
	}

	public static void removeNetBind(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		int pkey
	) throws IOException, SQLException {
		AccountingCode business = getBusinessForNetBind(conn, pkey);
		int server=getServerForNetBind(conn, pkey);

		if(conn.executeBooleanQuery("select (select net_bind from net_tcp_redirects where net_bind=?) is not null", pkey)) {
			conn.executeUpdate("delete from net_tcp_redirects where net_bind=?", pkey);
			invalidateList.addTable(
				conn,
				SchemaTable.TableID.NET_TCP_REDIRECTS,
				business,
				server,
				false
			);
		}

		if(conn.executeBooleanQuery("select (select net_bind from private_ftp_servers where net_bind=?) is not null", pkey)) {
			conn.executeUpdate("delete from private_ftp_servers where net_bind=?", pkey);
			invalidateList.addTable(
				conn,
				SchemaTable.TableID.PRIVATE_FTP_SERVERS,
				business,
				server,
				false
			);
		}

		conn.executeUpdate("delete from net_binds where pkey=?", pkey);
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.NET_BINDS,
			business,
			server,
			false
		);
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.NET_BIND_FIREWALLD_ZONES,
			business,
			server,
			false
		);
	}

	public static void setNetBindFirewalldZones(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int pkey,
		Set<FirewalldZoneName> firewalldZones
	) throws IOException, SQLException {
		PackageHandler.checkAccessPackage(conn, source, "setNetBindFirewalldZones", getPackageForNetBind(conn, pkey));

		boolean updated = false;
		int server = getServerForNetBind(conn, pkey);
		if(firewalldZones.isEmpty()) {
			if(conn.executeUpdate("delete from net_bind_firewalld_zones where net_bind=?", pkey) != 0) {
				updated = true;
			}
		} else {
			// Find the set that exists
			Set<FirewalldZoneName> existing = conn.executeObjectCollectionQuery(
				new HashSet<>(),
				ObjectFactories.firewalldZoneNameFactory,
				"select\n"
				+ "  fz.\"name\"\n"
				+ "from\n"
				+ "  net_bind_firewalld_zones nbfz\n"
				+ "  inner join firewalld_zones fz on nbfz.firewalld_zone=fz.pkey\n"
				+ "where\n"
				+ "  nbfz.net_bind=?",
				pkey
			);
			// Delete extra
			for(FirewalldZoneName name : existing) {
				if(!firewalldZones.contains(name)) {
					conn.executeUpdate(
						"delete from net_bind_firewalld_zones where pkey=(\n"
						+ "  select\n"
						+ "    nbfz.pkey\n"
						+ "  from\n"
						+ "    net_bind_firewalld_zones nbfz\n"
						+ "    inner join firewalld_zones fz on nbfz.firewalld_zone=fz.pkey\n"
						+ "  where\n"
						+ "    nbfz.net_bind=?\n"
						+ "    and fz.\"name\"=?\n"
						+ ")",
						pkey,
						name
					);
					updated = true;
				}
			}
			// Add new
			for(FirewalldZoneName name : firewalldZones) {
				if(!existing.contains(name)) {
					conn.executeUpdate(
						"insert into net_bind_firewalld_zones (net_bind, firewalld_zone) values (\n"
						+ "  ?,\n"
						+ "  (select pkey from firewalld_zones where server=? and \"name\"=?)\n"
						+ ")",
						pkey,
						server,
						name
					);
					updated = true;
				}
			}
		}
		if(updated) {
			AccountingCode business = getBusinessForNetBind(conn, pkey);
			invalidateList.addTable(
				conn,
				SchemaTable.TableID.NET_BINDS,
				business,
				server,
				false
			);
			invalidateList.addTable(
				conn,
				SchemaTable.TableID.NET_BIND_FIREWALLD_ZONES,
				business,
				server,
				false
			);
		}
	}

	public static void setNetBindMonitoringEnabled(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int pkey,
		boolean enabled
	) throws IOException, SQLException {
		PackageHandler.checkAccessPackage(conn, source, "setNetBindMonitoringEnabled", getPackageForNetBind(conn, pkey));

		conn.executeUpdate("update net_binds set monitoring_enabled=? where pkey=?", enabled, pkey);

		invalidateList.addTable(
			conn,
			SchemaTable.TableID.NET_BINDS,
			getBusinessForNetBind(conn, pkey),
			getServerForNetBind(conn, pkey),
			false
		);
	}

	/**
	 * This exists for compatibility with older clients (versions &lt;= 1.80.2) only.
	 * This has been implemented by adding and removing the public zone from the net_bind.
	 */
	public static void setNetBindOpenFirewall(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int pkey,
		boolean open_firewall
	) throws IOException, SQLException {
		AOServProtocol.Version clientVersion = source.getProtocolVersion();
		if(clientVersion.compareTo(AOServProtocol.Version.VERSION_1_80_2) > 0) {
			throw new IOException("This compatibility method only remains for clients version <= 1.80.2: Client is version " + clientVersion);
		}

		PackageHandler.checkAccessPackage(conn, source, "setNetBindOpenFirewall", getPackageForNetBind(conn, pkey));

		int server = getServerForNetBind(conn, pkey);
		if(open_firewall) {
			// Add the public zone if missing
			int fz = conn.executeIntQuery("select pkey from firewalld_zones where server=? and \"name\"=?", server, FirewalldZone.PUBLIC);
			boolean updated;
			synchronized(netBindLock) {
				if(
					conn.executeBooleanQuery("select (select pkey from net_bind_firewalld_zones where net_bind=? and firewalld_zone=?) is null", pkey, fz)
				) {
					conn.executeUpdate("insert into net_bind_firewalld_zones (net_bind, firewalld_zone) values (?,?)", pkey, fz);
					updated = true;
				} else {
					updated = false;
				}
			}
			if(updated) {
				AccountingCode business = getBusinessForNetBind(conn, pkey);
				invalidateList.addTable(
					conn,
					SchemaTable.TableID.NET_BINDS,
					business,
					server,
					false
				);
				invalidateList.addTable(
					conn,
					SchemaTable.TableID.NET_BIND_FIREWALLD_ZONES,
					business,
					server,
					false
				);
			}
		} else {
			// Remove the public zone if present
			if(
				conn.executeUpdate(
					"delete from net_bind_firewalld_zones where net_bind=? and firewalld_zone=(select pkey from firewalld_zones where server=? and \"name\"=?)",
					pkey,
					server,
					FirewalldZone.PUBLIC
				) != 0
			) {
				AccountingCode business = getBusinessForNetBind(conn, pkey);
				invalidateList.addTable(
					conn,
					SchemaTable.TableID.NET_BINDS,
					business,
					server,
					false
				);
				invalidateList.addTable(
					conn,
					SchemaTable.TableID.NET_BIND_FIREWALLD_ZONES,
					business,
					server,
					false
				);
			}
		}
	}
}
