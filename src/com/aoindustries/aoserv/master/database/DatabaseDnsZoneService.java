/*
 * Copyright 2010-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.database;

import com.aoindustries.aoserv.client.*;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.sql.ObjectFactory;
import java.rmi.RemoteException;
import java.sql.SQLException;
import java.util.ArrayList;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseDnsZoneService extends DatabaseResourceService<DnsZone> implements DnsZoneService {

    private static final String SELECT_COLUMNS =
        RESOURCE_SELECT_COLUMNS + ",\n"
        + "  dz.zone,\n"
        + "  dz.file,\n"
        + "  dz.hostmaster,\n"
        + "  dz.serial,\n"
        + "  dz.ttl"
    ;

    private final ObjectFactory<DnsZone> objectFactory = new AutoObjectFactory<DnsZone>(DnsZone.class, connector);

    DatabaseDnsZoneService(DatabaseConnector connector) {
        super(connector, DnsZone.class);
    }

    @Override
    protected ArrayList<DnsZone> getListMaster(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<DnsZone>(),
            objectFactory,
            "select\n"
            + SELECT_COLUMNS + "\n"
            + "from\n"
            + "  dns_zones dz\n"
            + "  inner join resources re on dz.resource=re.pkey"
        );
    }

    @Override
    protected ArrayList<DnsZone> getListDaemon(DatabaseConnection db) throws RemoteException, SQLException {
        MasterUser mu = connector.factory.rootConnector.getBusinessAdministrators().get(connector.getConnectAs()).getMasterUser();
        if(mu!=null && mu.isActive() && mu.isDnsAdmin()) {
            return db.executeObjectCollectionQuery(
                new ArrayList<DnsZone>(),
                objectFactory,
                "select\n"
                + SELECT_COLUMNS + "\n"
                + "from\n"
                + "  dns_zones dz\n"
                + "  inner join resources re on dz.resource=re.pkey"
            );
        } else {
            return new ArrayList<DnsZone>(0);
        }
    }

    @Override
    protected ArrayList<DnsZone> getListBusiness(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<DnsZone>(),
            objectFactory,
            "select\n"
            + SELECT_COLUMNS + "\n"
            + "from\n"
            + "  usernames un,\n"
            + BU1_PARENTS_JOIN
            + "  dns_zones dz\n"
            + "  inner join resources re on dz.resource=re.pkey\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and (\n"
            + UN_BU1_PARENTS_WHERE
            + "  )\n"
            + "  and bu1.accounting=dz.accounting",
            connector.getConnectAs()
        );
    }
}
