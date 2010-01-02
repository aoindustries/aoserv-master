package com.aoindustries.aoserv.master.database;

/*
 * Copyright 2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.TechnologyName;
import com.aoindustries.aoserv.client.TechnologyNameService;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.ObjectFactory;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseTechnologyNameService extends DatabaseServiceStringKey<TechnologyName> implements TechnologyNameService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<TechnologyName> objectFactory = new AutoObjectFactory<TechnologyName>(TechnologyName.class, this);

    DatabaseTechnologyNameService(DatabaseConnector connector) {
        super(connector, TechnologyName.class);
    }

    protected Set<TechnologyName> getSetMaster() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from technology_names"
        );
    }

    protected Set<TechnologyName> getSetDaemon() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from technology_names"
        );
    }

    protected Set<TechnologyName> getSetBusiness() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from technology_names"
        );
    }
}