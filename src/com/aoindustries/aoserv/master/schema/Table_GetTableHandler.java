/*
 * Copyright 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.schema;

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
public class Table_GetTableHandler extends TableHandler.GetTableHandlerPublic {

	@Override
	public Set<Table.TableID> getTableIds() {
		return EnumSet.of(Table.TableID.SCHEMA_TABLES);
	}

	@Override
	protected void getTablePublic(DatabaseConnection conn, RequestSource source, CompressedDataOutputStream out, boolean provideProgress, Table.TableID tableID) throws IOException, SQLException {
		MasterServer.writeObjects(
			conn,
			source,
			out,
			provideProgress,
			new Table(),
			"select\n"
			+ "  ROW_NUMBER() OVER (ORDER BY st.id) - 1 as \"id\",\n"
			+ "  st.\"name\",\n"
			+ "  st.\"sinceVersion\",\n"
			+ "  st.\"lastVersion\",\n"
			+ "  st.display,\n"
			+ "  st.\"isPublic\",\n"
			+ "  coalesce(st.description, d.description, '') as description\n"
			+ "from\n"
			+ "  \"schema\".\"AoservProtocol\" client_ap,\n"
			+ "             \"schema\".\"Table\"                        st\n"
			+ "  inner join \"schema\".\"Schema\"                        s on st.\"schema\"       =                s.id\n"
			+ "  inner join \"schema\".\"AoservProtocol\" \"sinceVersion\" on st.\"sinceVersion\" = \"sinceVersion\".version\n"
			+ "  left  join \"schema\".\"AoservProtocol\"  \"lastVersion\" on st.\"lastVersion\"  =  \"lastVersion\".version\n"
			+ "  left  join (\n"
			+ "    select\n"
			+ "      pn.nspname, pc.relname, pd.description\n"
			+ "    from\n"
			+ "                 pg_catalog.pg_namespace   pn\n"
			+ "      inner join pg_catalog.pg_class       pc on pn.oid = pc.relnamespace\n"
			+ "      inner join pg_catalog.pg_description pd on pc.oid = pd.objoid and pd.objsubid=0\n"
			+ "  ) d on (s.\"name\", st.\"name\") = (d.nspname, d.relname)\n"
			+ "where\n"
			+ "  client_ap.version=?\n"
			+ "  and client_ap.created >= \"sinceVersion\".created\n"
			+ "  and (\"lastVersion\".created is null or client_ap.created <= \"lastVersion\".created)\n"
			// TODO: This order by will probably not be necessary once the client orders with Comparable
			+ "order by\n"
			+ "  st.id",
			source.getProtocolVersion().getVersion()
		);
		/*
		List<Table> clientTables=new ArrayList<>();
		PreparedStatement pstmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(
			"select\n"
			+ "  st.id,\n"
			+ "  st.\"name\",\n"
			+ "  st.\"sinceVersion\",\n"
			+ "  st.\"lastVersion\",\n"
			+ "  st.display,\n"
			+ "  st.\"isPublic\",\n"
			+ "  coalesce(st.description, d.description, '') as description\n"
			+ "from\n"
			+ "  \"schema\".\"AoservProtocol\" client_ap,\n"
			+ "             \"schema\".\"Table\"                        st\n"
			+ "  inner join \"schema\".\"Schema\"                        s on st.\"schema\"       =                s.id\n"
			+ "  inner join \"schema\".\"AoservProtocol\" \"sinceVersion\" on st.\"sinceVersion\" = \"sinceVersion\".version\n"
			+ "  left  join \"schema\".\"AoservProtocol\"  \"lastVersion\" on st.\"lastVersion\"  =  \"lastVersion\".version\n"
			+ "  left  join (\n"
			+ "    select\n"
			+ "      pn.nspname, pc.relname, pd.description\n"
			+ "    from\n"
			+ "                 pg_catalog.pg_namespace   pn\n"
			+ "      inner join pg_catalog.pg_class       pc on pn.oid = pc.relnamespace\n"
			+ "      inner join pg_catalog.pg_description pd on pc.oid = pd.objoid and pd.objsubid=0\n"
			+ "  ) d on (s.\"name\", st.\"name\") = (d.nspname, d.relname)\n"
			+ "where\n"
			+ "  client_ap.version=?\n"
			+ "  and client_ap.created >= \"sinceVersion\".created\n"
			+ "  and (\"lastVersion\".created is null or client_ap.created <= \"lastVersion\".created)\n"
			+ "order by\n"
			+ "  st.id"
		);
		try {
			pstmt.setString(1, source.getProtocolVersion().getVersion());

			ResultSet results=pstmt.executeQuery();
			try {
				int clientTableID=0;
				Table tempST=new Table();
				while(results.next()) {
					tempST.init(results);
					clientTables.add(
						new Table(
							clientTableID++,
							tempST.getName(),
							tempST.getSinceVersion_version(),
							tempST.getLastVersion_version(),
							tempST.getDisplay(),
							tempST.isPublic(),
							tempST.getDescription()
						)
					);
				}
			} finally {
				results.close();
			}
		} catch(SQLException err) {
			System.err.println("Error from query: "+pstmt.toString());
			throw err;
		} finally {
			pstmt.close();
		}
		MasterServer.writeObjects(
			source,
			out,
			provideProgress,
			clientTables
		);
		 */
	}
}