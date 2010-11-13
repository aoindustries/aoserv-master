/*
 * Copyright 2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.database;

import com.aoindustries.aoserv.client.*;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.sql.ObjectFactory;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseHttpdSiteService extends DatabaseService<Integer,HttpdSite> implements HttpdSiteService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<HttpdSite> objectFactory = new AutoObjectFactory<HttpdSite>(HttpdSite.class, this);

    DatabaseHttpdSiteService(DatabaseConnector connector) {
        super(connector, Integer.class, HttpdSite.class);
    }

    @Override
    protected Set<HttpdSite> getSetMaster(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new HashSet<HttpdSite>(),
            objectFactory,
            "select\n"
            + "  ao_server_resource,\n"
            + "  site_name,\n"
            + "  list_first,\n"
            + "  linux_account_group,\n"
            + "  server_admin,\n"
            + "  is_manual_config,\n"
            + "  awstats_skip_files\n"
            + "from\n"
            + "  httpd_sites"
        );
    }

    @Override
    protected Set<HttpdSite> getSetDaemon(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new HashSet<HttpdSite>(),
            objectFactory,
            "select\n"
            + "  hs.ao_server_resource,\n"
            + "  hs.site_name,\n"
            + "  hs.list_first,\n"
            + "  hs.linux_account_group,\n"
            + "  hs.server_admin,\n"
            + "  hs.is_manual_config,\n"
            + "  hs.awstats_skip_files\n"
            + "from\n"
            + "  master_servers ms,\n"
            + "  httpd_sites hs\n"
            + "where\n"
            + "  ms.username=?\n"
            + "  and ms.server=hs.ao_server",
            connector.getConnectAs()
        );
    }

    @Override
    protected Set<HttpdSite> getSetBusiness(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new HashSet<HttpdSite>(),
            objectFactory,
            "select\n"
            + "  hs.ao_server_resource,\n"
            + "  hs.site_name,\n"
            + "  hs.list_first,\n"
            + "  hs.linux_account_group,\n"
            + "  hs.server_admin,\n"
            + "  hs.is_manual_config,\n"
            + "  hs.awstats_skip_files\n"
            + "from\n"
            + "  usernames un,\n"
            + BU1_PARENTS_JOIN
            + "  httpd_sites hs\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and (\n"
            + UN_BU1_PARENTS_WHERE
            + "  )\n"
            + "  and bu1.accounting=hs.accounting",
            connector.getConnectAs()
        );
    }
}