/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.linux;

import com.aoindustries.aoserv.client.linux.Group;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.master.UserHost;
import com.aoindustries.aoserv.client.schema.Table;
import com.aoindustries.aoserv.master.MasterServer;
import com.aoindustries.aoserv.master.RequestSource;
import com.aoindustries.aoserv.master.TableHandler;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.io.CompressedDataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
public class Group_GetTableHandler implements TableHandler.GetTableHandlerByRole {

	@Override
	public Set<Table.TableID> getTableIds() {
		return EnumSet.of(Table.TableID.LINUX_GROUPS);
	}

	@Override
	public void getTableMaster(DatabaseConnection conn, RequestSource source, CompressedDataOutputStream out, boolean provideProgress, Table.TableID tableID, User masterUser) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			new Group(),
			"select * from linux.\"Group\""
		);
	}

	@Override
	public void getTableDaemon(DatabaseConnection conn, RequestSource source, CompressedDataOutputStream out, boolean provideProgress, Table.TableID tableID, User masterUser, UserHost[] masterServers) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			new Group(),
			"select distinct\n"
			+ "  lg.*\n"
			+ "from\n"
			+ "  master.\"UserHost\" ms,\n"
			+ "  linux.\"Server\" ao,\n"
			+ "  account.\"AccountHost\" bs,\n"
			+ "  billing.\"Package\" pk,\n"
			+ "  linux.\"Group\" lg\n"
			+ "where\n"
			+ "  ms.username=?\n"
			+ "  and ms.server=ao.server\n"
			+ "  and ao.server=bs.server\n"
			+ "  and bs.accounting=pk.accounting\n"
			+ "  and pk.name=lg.package",
			source.getUsername()
		);
	}

	@Override
	public void getTableAdministrator(DatabaseConnection conn, RequestSource source, CompressedDataOutputStream out, boolean provideProgress, Table.TableID tableID) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			new Group(),
			"select\n"
			+ "  lg.*\n"
			+ "from\n"
			+ "  account.\"Username\" un,\n"
			+ "  billing.\"Package\" pk1,\n"
			+ TableHandler.BU1_PARENTS_JOIN
			+ "  billing.\"Package\" pk2,\n"
			+ "  linux.\"Group\" lg\n"
			+ "where\n"
			+ "  un.username=?\n"
			+ "  and un.package=pk1.name\n"
			+ "  and (\n"
			+ "    lg.name in (?,?,?)\n"
			+ TableHandler.PK1_BU1_PARENTS_OR_WHERE
			+ "  )\n"
			+ "  and bu1.accounting=pk2.accounting\n"
			+ "  and pk2.name=lg.package",
			source.getUsername(),
			Group.FTPONLY,
			Group.MAIL,
			Group.MAILONLY
		);
	}
}
