/*
 * Copyright 2010-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.database;

import com.aoindustries.aoserv.client.*;
import com.aoindustries.aoserv.client.validator.*;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.sql.ObjectFactory;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseShellService extends DatabaseService<UnixPath,Shell> implements ShellService {

    private final ObjectFactory<Shell> objectFactory = new AutoObjectFactory<Shell>(Shell.class, connector);

    DatabaseShellService(DatabaseConnector connector) {
        super(connector, UnixPath.class, Shell.class);
    }

    @Override
    protected List<Shell> getList(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<Shell>(),
            objectFactory,
            "select * from shells"
        );
    }
}
