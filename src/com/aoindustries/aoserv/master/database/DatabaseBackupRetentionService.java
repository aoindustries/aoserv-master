/*
 * Copyright 2009-2010 by AO Industries, Inc.,
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
final class DatabaseBackupRetentionService extends DatabasePublicService<Short,BackupRetention> implements BackupRetentionService {

    private final ObjectFactory<BackupRetention> objectFactory = new AutoObjectFactory<BackupRetention>(BackupRetention.class, connector);

    DatabaseBackupRetentionService(DatabaseConnector connector) {
        super(connector, Short.class, BackupRetention.class);
    }

    @Override
    protected ArrayList<BackupRetention> getPublicList(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<BackupRetention>(),
            objectFactory,
            "select * from backup_retentions"
        );
    }
}
