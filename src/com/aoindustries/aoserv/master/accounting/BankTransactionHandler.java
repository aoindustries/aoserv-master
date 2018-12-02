/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.accounting;

import com.aoindustries.aoserv.client.accounting.BankTransaction;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.master.UserHost;
import com.aoindustries.aoserv.client.schema.AoservProtocol;
import com.aoindustries.aoserv.client.schema.Table;
import com.aoindustries.aoserv.master.BankAccountHandler;
import com.aoindustries.aoserv.master.CursorMode;
import com.aoindustries.aoserv.master.MasterServer;
import com.aoindustries.aoserv.master.RequestSource;
import com.aoindustries.aoserv.master.TableHandler;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
public class BankTransactionHandler {

	private BankTransactionHandler() {}

	private static final String QUERY_ACCOUNTING =
		"select\n"
		+ "  id,\n"
		+ "  time,\n"
		+ "  account,\n"
		+ "  processor,\n"
		+ "  administrator,\n"
		+ "  type,\n"
		+ "  \"expenseCategory\",\n"
		+ "  description,\n"
		+ "  \"checkNo\",\n"
		+ "  amount,\n"
		+ "  confirmed\n"
		+ "from\n"
		+ "  accounting.\"BankTransaction\"";

	static public class GetObject implements TableHandler.GetObjectHandler {

		@Override
		public Set<Table.TableID> getTableIds() {
			return EnumSet.of(Table.TableID.BANK_TRANSACTIONS);
		}

		@Override
		public void getObject(DatabaseConnection conn, RequestSource source, CompressedDataInputStream in, CompressedDataOutputStream out, Table.TableID tableID, User masterUser, UserHost[] masterServers) throws IOException, SQLException {
			int id = in.readCompressedInt();
			if(BankAccountHandler.isBankAccounting(conn, source)) {
				MasterServer.writeObject(
					conn,
					source,
					out,
					new BankTransaction(),
					QUERY_ACCOUNTING + "\n"
					+ "where\n"
					+ "  id=?",
					id
				);
			} else {
				out.writeByte(AoservProtocol.DONE);
			}
		}
	}

	static public class BankTransaction_GetTableHandler implements GetTableHandlerAccountingOnly {

		@Override
		public Set<Table.TableID> getTableIds() {
			return EnumSet.of(Table.TableID.BANK_TRANSACTIONS);
		}

		@Override
		public void getTableAccounting(DatabaseConnection conn, RequestSource source, CompressedDataOutputStream out, boolean provideProgress, Table.TableID tableID, User masterUser) throws IOException, SQLException {
			MasterServer.writeObjects(
				conn,
				source,
				out,
				provideProgress,
				CursorMode.FETCH,
				new BankTransaction(),
				QUERY_ACCOUNTING
			);
		}
	}
}
