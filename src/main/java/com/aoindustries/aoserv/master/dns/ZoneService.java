/*
 * aoserv-master - Master server for the AOServ Platform.
 * Copyright (C) 2018, 2019, 2020  AO Industries, Inc.
 *     support@aoindustries.com
 *     7262 Bull Pen Cir
 *     Mobile, AL 36695
 *
 * This file is part of aoserv-master.
 *
 * aoserv-master is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * aoserv-master is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with aoserv-master.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.aoindustries.aoserv.master.dns;

import com.aoindustries.aoserv.client.account.Account;
import com.aoindustries.aoserv.client.dns.Zone;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.master.UserHost;
import com.aoindustries.aoserv.client.schema.Table;
import com.aoindustries.aoserv.master.CursorMode;
import com.aoindustries.aoserv.master.MasterServer;
import com.aoindustries.aoserv.master.MasterService;
import com.aoindustries.aoserv.master.RequestSource;
import com.aoindustries.aoserv.master.TableHandler;
import com.aoindustries.aoserv.master.billing.WhoisHistoryDomainLocator;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.io.stream.StreamableOutput;
import com.aoindustries.net.DomainName;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
public class ZoneService implements MasterService, WhoisHistoryDomainLocator {

	// <editor-fold desc="GetTableHandler" defaultstate="collapsed">
	@Override
	public TableHandler.GetTableHandler startGetTableHandler() {
		return new TableHandler.GetTableHandlerByRole() {
			@Override
			public Set<Table.TableID> getTableIds() {
				return EnumSet.of(Table.TableID.DNS_ZONES);
			}

			private void getTableUnfiltered(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID) throws IOException, SQLException {
				MasterServer.writeObjects(
					conn,
					source,
					out,
					provideProgress,
					CursorMode.AUTO,
					new Zone(),
					"select * from dns.\"Zone\""
				);
			}

			@Override
			protected void getTableMaster(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID, User masterUser) throws IOException, SQLException {
				getTableUnfiltered(conn, source, out, provideProgress, tableID);
			}

			@Override
			protected void getTableDaemon(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID, User masterUser, UserHost[] masterServers) throws IOException, SQLException {
				if(masterUser.isDNSAdmin()) {
					getTableUnfiltered(conn, source, out, provideProgress, tableID);
				} else {
					MasterServer.writeObjects(source, out, provideProgress, Collections.emptyList());
				}
			}

			@Override
			protected void getTableAdministrator(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID) throws IOException, SQLException {
				MasterServer.writeObjects(
					conn,
					source,
					out,
					provideProgress,
					CursorMode.AUTO,
					new Zone(),
					"select\n"
					+ "  dz.*\n"
					+ "from\n"
					+ "  account.\"User\" un,\n"
					+ "  billing.\"Package\" pk1,\n"
					+ TableHandler.BU1_PARENTS_JOIN
					+ "  billing.\"Package\" pk2,\n"
					+ "  dns.\"Zone\" dz\n"
					+ "where\n"
					+ "  un.username=?\n"
					+ "  and un.package=pk1.name\n"
					+ "  and (\n"
					+ TableHandler.PK1_BU1_PARENTS_WHERE
					+ "  )\n"
					+ "  and bu1.accounting=pk2.accounting\n"
					+ "  and pk2.name=dz.package",
					source.getCurrentAdministrator()
				);
			}
		};
	}
	// </editor-fold>

	// <editor-fold desc="WhoisHistoryDomainLocator" defaultstate="collapsed">
	@Override
	public Map<DomainName,Set<Account.Name>> getWhoisHistoryDomains(DatabaseConnection conn) throws IOException, SQLException {
		return conn.queryCall(
			(ResultSet results) -> {
				try {
					Map<DomainName,Set<Account.Name>> map = new HashMap<>();
					while(results.next()) {
						String zone = results.getString(1);
						// Strip any trailing period
						if(zone.endsWith(".")) zone = zone.substring(0, zone.length() - 1);
						DomainName domain = DomainName.valueOf(zone);
						Account.Name account = Account.Name.valueOf(results.getString(2));
						// We consider all in dns.Zone table as registrable and use them verbatim for whois lookups
						Set<Account.Name> accounts = map.get(domain);
						if(accounts == null) map.put(domain, accounts = new LinkedHashSet<>());
						accounts.add(account);
					}
					return map;
				} catch(ValidationException e) {
					throw new SQLException(e);
				}
			},
			"SELECT DISTINCT\n"
			+ "  dz.\"zone\",\n"
			+ "  pk.accounting\n"
			+ "FROM\n"
			+ "  dns.\"Zone\" dz\n"
			+ "  INNER JOIN billing.\"Package\" pk ON dz.package = pk.\"name\"\n"
			+ "WHERE\n"
			+ "  dz.\"zone\" NOT LIKE '%.in-addr.arpa'"
		);
	}
	// </editor-fold>
}
