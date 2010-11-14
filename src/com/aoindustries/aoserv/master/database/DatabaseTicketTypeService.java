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
final class DatabaseTicketTypeService extends DatabasePublicService<String,TicketType> implements TicketTypeService {

    private final ObjectFactory<TicketType> objectFactory = new AutoObjectFactory<TicketType>(TicketType.class, connector);

    DatabaseTicketTypeService(DatabaseConnector connector) {
        super(connector, String.class, TicketType.class);
    }

    @Override
    protected ArrayList<TicketType> getPublicList(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArrayList<TicketType>(),
            objectFactory,
            "select * from ticket_types"
        );
    }
}
