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
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseLinuxAccountTypeService extends DatabaseService<String,LinuxAccountType> implements LinuxAccountTypeService {

    private final ObjectFactory<LinuxAccountType> objectFactory = new AutoObjectFactory<LinuxAccountType>(LinuxAccountType.class, connector);

    DatabaseLinuxAccountTypeService(DatabaseConnector connector) {
        super(connector, String.class, LinuxAccountType.class);
    }

    @Override
    protected List<LinuxAccountType> getList(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<LinuxAccountType>(),
            objectFactory,
            "select * from linux_account_types"
        );
    }
}
