/*
 * Copyright 2009-2010 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.database;

import com.aoindustries.aoserv.client.TicketCategory;
import com.aoindustries.aoserv.client.TicketCategoryService;
import com.aoindustries.sql.AutoObjectFactory;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.sql.ObjectFactory;
import com.aoindustries.util.ArraySet;
import com.aoindustries.util.HashCodeComparator;
import java.sql.SQLException;
import java.util.Set;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseTicketCategoryService extends DatabasePublicService<Integer,TicketCategory> implements TicketCategoryService<DatabaseConnector,DatabaseConnectorFactory> {

    private final ObjectFactory<TicketCategory> objectFactory = new AutoObjectFactory<TicketCategory>(TicketCategory.class, this);

    DatabaseTicketCategoryService(DatabaseConnector connector) {
        super(connector, Integer.class, TicketCategory.class);
    }

    @Override
    protected Set<TicketCategory> getPublicSet(DatabaseConnection db) throws SQLException {
        return db.executeObjectSetQuery(
            new ArraySet<TicketCategory>(HashCodeComparator.getInstance()),
            objectFactory,
            "select * from ticket_categories order by pkey"
        );
    }
}
