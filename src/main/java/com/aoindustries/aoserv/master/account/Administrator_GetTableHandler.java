/*
 * aoserv-master - Master server for the AOServ Platform.
 * Copyright (C) 2018, 2019  AO Industries, Inc.
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
package com.aoindustries.aoserv.master.account;

import com.aoindustries.aoserv.client.account.Administrator;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.master.UserHost;
import com.aoindustries.aoserv.client.schema.Table;
import com.aoindustries.aoserv.master.CursorMode;
import com.aoindustries.aoserv.master.MasterServer;
import com.aoindustries.aoserv.master.RequestSource;
import com.aoindustries.aoserv.master.TableHandler;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.io.stream.StreamableOutput;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
public class Administrator_GetTableHandler extends TableHandler.GetTableHandlerByRole {

	@Override
	public Set<Table.TableID> getTableIds() {
		return EnumSet.of(Table.TableID.BUSINESS_ADMINISTRATORS);
	}
	@Override
	protected void getTableMaster(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID, User masterUser) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			CursorMode.AUTO,
			new Administrator(),
			"select * from account.\"Administrator\""
		);
	}

	@Override
	protected void getTableDaemon(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID, User masterUser, UserHost[] masterServers) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			CursorMode.AUTO,
			new Administrator(),
			"select distinct\n"
			+ "  ba.username,\n"
			+ "  null::text,\n"
			+ "  ba.name,\n"
			+ "  ba.title,\n"
			+ "  ba.birthday,\n"
			+ "  ba.is_preferred,\n"
			+ "  ba.private,\n"
			+ "  ba.created,\n"
			+ "  ba.work_phone,\n"
			+ "  ba.home_phone,\n"
			+ "  ba.cell_phone,\n"
			+ "  ba.fax,\n"
			+ "  ba.email,\n"
			+ "  ba.address1,\n"
			+ "  ba.address2,\n"
			+ "  ba.city,\n"
			+ "  ba.state,\n"
			+ "  ba.country,\n"
			+ "  ba.zip,\n"
			+ "  ba.disable_log,\n"
			+ "  ba.can_switch_users,\n"
			+ "  null\n"
			+ "from\n"
			+ "  master.\"UserHost\" ms,\n"
			+ "  account.\"AccountHost\" bs,\n"
			+ "  billing.\"Package\" pk,\n"
			+ "  account.\"User\" un,\n"
			+ "  account.\"Administrator\" ba\n"
			+ "where\n"
			+ "  ms.username=?\n"
			+ "  and ms.server=bs.server\n"
			+ "  and bs.accounting=pk.accounting\n"
			+ "  and pk.name=un.package\n"
			+ "  and un.username=ba.username",
			source.getCurrentAdministrator()
		);
	}

	@Override
	protected void getTableAdministrator(DatabaseConnection conn, RequestSource source, StreamableOutput out, boolean provideProgress, Table.TableID tableID) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			CursorMode.AUTO,
			new Administrator(),
			"select\n"
			+ "  ba.username,\n"
			+ "  null::text,\n"
			+ "  ba.name,\n"
			+ "  ba.title,\n"
			+ "  ba.birthday,\n"
			+ "  ba.is_preferred,\n"
			+ "  ba.private,\n"
			+ "  ba.created,\n"
			+ "  ba.work_phone,\n"
			+ "  ba.home_phone,\n"
			+ "  ba.cell_phone,\n"
			+ "  ba.fax,\n"
			+ "  ba.email,\n"
			+ "  ba.address1,\n"
			+ "  ba.address2,\n"
			+ "  ba.city,\n"
			+ "  ba.state,\n"
			+ "  ba.country,\n"
			+ "  ba.zip,\n"
			+ "  ba.disable_log,\n"
			+ "  ba.can_switch_users,\n"
			+ "  ba.support_code\n"
			+ "from\n"
			+ "  account.\"User\" un1,\n"
			+ "  billing.\"Package\" pk1,\n"
			+ TableHandler.BU1_PARENTS_JOIN
			+ "  billing.\"Package\" pk2,\n"
			+ "  account.\"User\" un2,\n"
			+ "  account.\"Administrator\" ba\n"
			+ "where\n"
			+ "  un1.username=?\n"
			+ "  and un1.package=pk1.name\n"
			+ "  and (\n"
			+ "    un2.username=un1.username\n"
			+ TableHandler.PK1_BU1_PARENTS_OR_WHERE
			+ "  )\n"
			+ "  and bu1.accounting=pk2.accounting\n"
			+ "  and pk2.name=un2.package\n"
			+ "  and un2.username=ba.username",
			source.getCurrentAdministrator()
		);
	}
}
