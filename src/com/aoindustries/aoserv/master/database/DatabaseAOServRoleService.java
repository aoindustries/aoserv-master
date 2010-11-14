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
import java.util.ArrayList;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseAOServRoleService extends DatabaseService<Integer,AOServRole> implements AOServRoleService {

    private final ObjectFactory<AOServRole> objectFactory = new AutoObjectFactory<AOServRole>(AOServRole.class, connector);

    DatabaseAOServRoleService(DatabaseConnector connector) {
        super(connector, Integer.class, AOServRole.class);
    }

    @Override
    protected ArrayList<AOServRole> getListMaster(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<AOServRole>(),
            objectFactory,
            "select * from aoserv_roles"
        );
    }

    /**
     * Can only see their own roles.
     */
    @Override
    protected ArrayList<AOServRole> getListDaemon(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<AOServRole>(),
            objectFactory,
            "select\n"
            + "  ar.*\n"
            + "from\n"
            + "  business_administrator_roles bar\n"
            + "  inner join aoserv_roles ar on bar.role=ar.pkey\n"
            + "where\n"
            + "  bar.username=?",
            connector.getConnectAs()
        );
    }

    /**
     * Can see the roles owned by the business tree and their own roles.
     */
    @Override
    protected ArrayList<AOServRole> getListBusiness(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<AOServRole>(),
            objectFactory,
            // Business-based
            "select\n"
            + "  ar.*\n"
            + "from\n"
            + "  usernames un,\n"
            + BU1_PARENTS_JOIN
            + "  aoserv_roles ar\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and (\n"
            + UN_BU1_PARENTS_WHERE
            + "  )\n"
            + "  and bu1.accounting=ar.accounting\n"
            // Individual role-based
            + "union select\n"
            + "  ar.*\n"
            + "from\n"
            + "  usernames un1,\n"
            + BU1_PARENTS_JOIN
            + "  business_administrators ba,\n"
            + "  business_administrator_roles bar,\n"
            + "  aoserv_roles ar\n"
            + "where\n"
            + "  un1.username=?\n"
            + "  and (\n"
            + "    ba.username=un1.username\n"
            + UN1_BU1_PARENTS_OR_WHERE
            + "  )\n"
            + "  and bu1.accounting=ba.accounting\n"
            + "  and ba.username=bar.username\n"
            + "  and bar.role=ar.pkey",
            connector.getConnectAs(),
            connector.getConnectAs()
        );
    }
}
