/*
 * Copyright 2009-2011 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master.database;

import com.aoindustries.aoserv.client.*;

/**
 * @author  AO Industries, Inc.
 */
final class DatabaseResourceService extends ResourceService {

    static final String SELECT_COLUMNS =
        "  re.pkey,\n"
        + "  re.resource_type,\n"
        + "  re.accounting,\n"
        + "  (extract(epoch from re.created)*1000)::int8 as created,\n"
        + "  re.created_by,\n"
        + "  re.disable_log,\n"
        + "  (extract(epoch from re.last_enabled)*1000)::int8 as last_enabled,\n"
    ;
    /* TODO
    private final ObjectFactory<Resource> objectFactory = new ObjectFactory<Resource>() {
        @Override
        public Resource createObject(ResultSet result) throws SQLException {
            try {
                return new Resource(
                    DatabaseResourceService.this,
                    result.getInt("pkey"),
                    result.getString("resource_type"),
                    AccountingCode.valueOf(result.getString("accounting")),
                    result.getLong("created"),
                    UserId.valueOf(result.getString("created_by")),
                    (Integer)result.getObject("disable_log"),
                    result.getLong("last_enabled")
                );
            } catch(ValidationException err) {
                throw new SQLException(err);
            }
        }
    };
    */

    DatabaseResourceService(DatabaseConnector connector) {
        super(connector);
    }

    /* TODO
    @Override
    protected ArrayList<Resource> getListMaster(DatabaseConnection db) throws SQLException {
        return db.executeObjectCollectionQuery(
            new ArraySet<Resource>(),
            objectFactory,
            "select\n"
            + "  pkey,\n"
            + "  resource_type,\n"
            + "  accounting,\n"
            + "  (extract(epoch from created)*1000)::int8 as created,\n"
            + "  created_by,\n"
            + "  disable_log,\n"
            + "  (extract(epoch from last_enabled)*1000)::int8 as last_enabled\n"
            + "from\n"
            + "  resources\n"
            + "  order by\n"
            + "pkey"
        );
    }

    @Override
    protected ArrayList<Resource> getListDaemon(DatabaseConnection db) throws SQLException {
        StringBuilder sql = new StringBuilder(
            // ao_server_resources
            "select\n"
            + "  re.pkey,\n"
            + "  re.resource_type,\n"
            + "  re.accounting,\n"
            + "  (extract(epoch from re.created)*1000)::int8 as created,\n"
            + "  re.created_by,\n"
            + "  re.disable_log,\n"
            + "  (extract(epoch from re.last_enabled)*1000)::int8 as last_enabled\n"
            + "from\n"
            + "  master_servers ms,\n"
            + "  ao_server_resources asr,\n"
            + "  resources re\n"
            + "where\n"
            + "  ms.username=?\n"
            + "  and ms.server=asr.ao_server\n"
            + "  and asr.resource=re.pkey\n"
            // server_resources
            + "union select\n"
            + "  re.pkey,\n"
            + "  re.resource_type,\n"
            + "  re.accounting,\n"
            + "  (extract(epoch from re.created)*1000)::int8 as created,\n"
            + "  re.created_by,\n"
            + "  re.disable_log,\n"
            + "  (extract(epoch from re.last_enabled)*1000)::int8 as last_enabled\n"
            + "from\n"
            + "  master_servers ms,\n"
            + "  server_resources sr,\n"
            + "  resources re\n"
            + "where\n"
            + "  ms.username=?\n"
            + "  and ms.server=sr.server\n"
            + "  and sr.resource=re.pkey\n"
        );
        List<Set<? extends AOServObject<Integer>>> extraResources = new ArrayList<Set<? extends AOServObject<Integer>>>();
        connector.serverResources.addExtraServerResourcesDaemon(db, extraResources);
        addOptionalInInteger(
            sql,
            "union select\n"
            + "  pkey,\n"
            + "  resource_type,\n"
            + "  accounting,\n"
            + "  (extract(epoch from created)*1000)::int8 as created,\n"
            + "  created_by,\n"
            + "  disable_log,\n"
            + "  (extract(epoch from last_enabled)*1000)::int8 as last_enabled\n"
            + "from\n"
            + "  resources\n"
            + "where\n"
            + "  pkey in (",
            extraResources,
            ")\n"
        );
        sql.append("order by\n"
                + "  pkey");
        return db.executeObjectCollectionQuery(
            new ArraySet<Resource>(),
            objectFactory,
            sql.toString(),
            connector.getConnectAs(),
            connector.getConnectAs()
        );
    }

    @Override
    protected ArrayList<Resource> getListBusiness(DatabaseConnection db) throws SQLException {
        // owns the resource
        StringBuilder sql = new StringBuilder(
            "select\n"
            + "  re.pkey,\n"
            + "  re.resource_type,\n"
            + "  re.accounting,\n"
            + "  (extract(epoch from re.created)*1000)::int8 as created,\n"
            + "  re.created_by,\n"
            + "  re.disable_log,\n"
            + "  (extract(epoch from re.last_enabled)*1000)::int8 as last_enabled\n"
            + "from\n"
            + "  usernames un,\n"
            + BU1_PARENTS_JOIN
            + "  resources re\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and (\n"
            + UN_BU1_PARENTS_WHERE
            + "  )\n"
            + "  and bu1.accounting=re.accounting\n"
        );
        List<Set<? extends AOServObject<Integer>>> extraResources = new ArrayList<Set<? extends AOServObject<Integer>>>();
        connector.serverResources.addExtraServerResourcesBusiness(db, extraResources);
        connector.aoserverResources.addExtraAOServerResourcesBusiness(db, extraResources);
        addOptionalInInteger(
            sql,
            "union select\n"
            + "  pkey,\n"
            + "  resource_type,\n"
            + "  accounting,\n"
            + "  (extract(epoch from created)*1000)::int8 as created,\n"
            + "  created_by,\n"
            + "  disable_log,\n"
            + "  (extract(epoch from last_enabled)*1000)::int8 as last_enabled\n"
            + "from\n"
            + "  resources\n"
            + "where\n"
            + "  pkey in (",
            extraResources,
            ")\n"
        );
        sql.append("order by\n"
                + "  pkey");
        return db.executeObjectCollectionQuery(
            new ArraySet<Resource>(),
            objectFactory,
            sql.toString(),
            connector.getConnectAs()
        );
    }
     */
}
