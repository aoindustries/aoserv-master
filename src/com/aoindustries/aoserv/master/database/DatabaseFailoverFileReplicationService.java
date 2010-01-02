package com.aoindustries.aoserv.master.database;

/*
 * Copyright 2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.FailoverFileReplication;
import com.aoindustries.aoserv.client.FailoverFileReplicationService;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.ObjectFactory;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseFailoverFileReplicationService extends DatabaseServiceIntegerKey<FailoverFileReplication> implements FailoverFileReplicationService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<FailoverFileReplication> objectFactory = new AutoObjectFactory<FailoverFileReplication>(FailoverFileReplication.class, this);

    DatabaseFailoverFileReplicationService(DatabaseConnector connector) {
        super(connector, FailoverFileReplication.class);
    }

    protected Set<FailoverFileReplication> getSetMaster() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from failover_file_replications"
        );
    }

    protected Set<FailoverFileReplication> getSetDaemon() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select\n"
            + "  ffr.*\n"
            + "from\n"
            + "  master_servers ms,\n"
            + "  failover_file_replications ffr\n"
            + "where\n"
            + "  ms.username=?\n"
            + "  and ms.server=ffr.server",
            connector.getConnectAs()
        );
    }

    protected Set<FailoverFileReplication> getSetBusiness() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select\n"
            + "  ffr.*\n"
            + "from\n"
            + "  usernames un,\n"
            + "  business_servers bs,\n"
            + "  failover_file_replications ffr\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and un.accounting=bs.accounting\n"
            + "  and bs.server=ffr.server",
            connector.getConnectAs()
        );
    }
}