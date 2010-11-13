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
import com.aoindustries.util.ArraySet;
import java.sql.SQLException;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseMajordomoVersionService extends DatabasePublicService<String,MajordomoVersion> implements MajordomoVersionService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<MajordomoVersion> objectFactory = new AutoObjectFactory<MajordomoVersion>(MajordomoVersion.class, this);

    DatabaseMajordomoVersionService(DatabaseConnector connector) {
        super(connector, String.class, MajordomoVersion.class);
    }

    @Override
    protected Set<MajordomoVersion> getPublicSet(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArraySet<MajordomoVersion>(),
            objectFactory,
            "select\n"
            + "  version,\n"
            + "  (extract(epoch from created)*1000)::int8 as created\n"
            + "from\n"
            + "  majordomo_versions\n"
            + "order by\n"
            + "  version"
        );
    }
}