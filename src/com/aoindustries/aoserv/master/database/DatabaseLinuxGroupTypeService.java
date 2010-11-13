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
final class DatabaseLinuxGroupTypeService extends DatabasePublicService<String,LinuxGroupType> implements LinuxGroupTypeService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<LinuxGroupType> objectFactory = new AutoObjectFactory<LinuxGroupType>(LinuxGroupType.class, this);

    DatabaseLinuxGroupTypeService(DatabaseConnector connector) {
        super(connector, String.class, LinuxGroupType.class);
    }

    @Override
    protected Set<LinuxGroupType> getPublicSet(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new HashSet<LinuxGroupType>(),
            objectFactory,
            "select * from linux_group_types"
        );
    }
}