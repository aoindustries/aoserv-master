package com.aoindustries.aoserv.master.database;

/*
 * Copyright 2009-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServerResource;
import com.aoindustries.aoserv.client.AOServerResourceService;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.ObjectFactory;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseAOServerResourceService extends DatabaseServiceIntegerKey<AOServerResource> implements AOServerResourceService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<AOServerResource> objectFactory = new AutoObjectFactory<AOServerResource>(AOServerResource.class, this);

    DatabaseAOServerResourceService(DatabaseConnector connector) {
        super(connector, AOServerResource.class);
    }

    protected Set<AOServerResource> getSetMaster() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select\n"
            + "  asr.resource,\n"
            + "  asr.ao_server,\n"
            + "  bs.pkey\n"
            + "from\n"
            + "  ao_server_resources asr\n"
            + "  inner join business_servers bs on asr.accounting=bs.accounting and asr.ao_server=bs.server"
        );
    }

    protected Set<AOServerResource> getSetDaemon() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select\n"
            + "  asr.resource,\n"
            + "  asr.ao_server,\n"
            + "  bs.pkey\n"
            + "from\n"
            + "  master_servers ms,\n"
            + "  ao_server_resources asr\n"
            + "  inner join business_servers bs on asr.accounting=bs.accounting and asr.ao_server=bs.server\n"
            + "where\n"
            + "  ms.username=?\n"
            + "  and ms.server=asr.ao_server",
            connector.getConnectAs()
        );
    }

    protected Set<AOServerResource> getSetBusiness() throws IOException, SQLException {
        // owns the resource
        StringBuilder sql = new StringBuilder(
            "select\n"
            + "  asr.resource,\n"
            + "  asr.ao_server,\n"
            + "  bs.pkey\n"
            + "from\n"
            + "  usernames un,\n"
            + BU1_PARENTS_JOIN
            + "  ao_server_resources asr\n"
            + "  inner join business_servers bs on asr.accounting=bs.accounting and asr.ao_server=bs.server\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and (\n"
            + UN_BU1_PARENTS_WHERE
            + "  ) and (\n"
            + "    bu1.accounting=asr.accounting\n"
        );
        addOptionalInInteger(sql, "    or asr.resource in (", connector.linuxGroups.getSetBusiness(), ")\n");
        addOptionalInInteger(sql, "    or asr.resource in (", connector.mysqlServers.getSetBusiness(), ")\n");
        addOptionalInInteger(sql, "    or asr.resource in (", connector.postgresServers.getSetBusiness(), ")\n");
        sql.append("  )");
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            sql.toString(),
            connector.getConnectAs()
        );
    }
}
