package com.aoindustries.aoserv.master.database;

/*
 * Copyright 2009 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.Language;
import com.aoindustries.aoserv.client.LanguageService;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.ObjectFactory;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseLanguageService extends DatabaseServiceStringKey<Language> implements LanguageService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<Language> objectFactory = new AutoObjectFactory<Language>(Language.class, this);

    DatabaseLanguageService(DatabaseConnector connector) {
        super(connector, Language.class);
    }

    protected Set<Language> getSetMaster() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from languages"
        );
    }

    protected Set<Language> getSetDaemon() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from languages"
        );
    }

    protected Set<Language> getSetBusiness() throws IOException, SQLException {
        return connector.factory.database.executeObjectSetQuery(
            objectFactory,
            "select * from languages"
        );
    }
}
