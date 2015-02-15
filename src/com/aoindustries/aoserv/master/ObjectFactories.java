/*
 * Copyright 2013, 2015 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master;

import com.aoindustries.aoserv.client.validator.AccountingCode;
import com.aoindustries.aoserv.client.validator.DomainName;
import com.aoindustries.aoserv.client.validator.HostAddress;
import com.aoindustries.aoserv.client.validator.InetAddress;
import com.aoindustries.aoserv.client.validator.ValidationException;
import com.aoindustries.dbc.ObjectFactory;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * A set of object factories for various types.
 *
 * @author  AO Industries, Inc.
 */
final public class ObjectFactories {
    
    /**
     * Make no instances.
     */
    private ObjectFactories() {
    }

    public static final ObjectFactory<AccountingCode> accountingCodeFactory = new ObjectFactory<AccountingCode>() {
		@Override
        public AccountingCode createObject(ResultSet result) throws SQLException {
            try {
                return AccountingCode.valueOf(result.getString(1));
            } catch(ValidationException e) {
                SQLException exc = new SQLException(e.getLocalizedMessage());
                exc.initCause(e);
                throw exc;
            }
        }
    };

    public static final ObjectFactory<DomainName> domainNameFactory = new ObjectFactory<DomainName>() {
		@Override
        public DomainName createObject(ResultSet result) throws SQLException {
            try {
                return DomainName.valueOf(result.getString(1));
            } catch(ValidationException e) {
                SQLException exc = new SQLException(e.getLocalizedMessage());
                exc.initCause(e);
                throw exc;
            }
        }
    };

    public static final ObjectFactory<HostAddress> hostAddressFactory = new ObjectFactory<HostAddress>() {
		@Override
        public HostAddress createObject(ResultSet result) throws SQLException {
            try {
                return HostAddress.valueOf(result.getString(1));
            } catch(ValidationException e) {
                SQLException exc = new SQLException(e.getLocalizedMessage());
                exc.initCause(e);
                throw exc;
            }
        }
    };

    public static final ObjectFactory<InetAddress> inetAddresFactory = new ObjectFactory<InetAddress>() {
		@Override
        public InetAddress createObject(ResultSet result) throws SQLException {
            try {
                return InetAddress.valueOf(result.getString(1));
            } catch(ValidationException e) {
                SQLException exc = new SQLException(e.getLocalizedMessage());
                exc.initCause(e);
                throw exc;
            }
        }
    };
}
