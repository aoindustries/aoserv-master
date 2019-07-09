/*
 * Copyright 2001-2013, 2015, 2017, 2018, 2019 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master;

import com.aoindustries.aoserv.client.account.Account;
import com.aoindustries.aoserv.client.billing.Package;
import com.aoindustries.aoserv.client.billing.Resource;
import com.aoindustries.aoserv.client.master.User;
import com.aoindustries.aoserv.client.master.UserHost;
import com.aoindustries.aoserv.client.schema.Table;
import com.aoindustries.dbc.DatabaseAccess;
import com.aoindustries.dbc.DatabaseAccess.Null;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.sql.WrappedSQLException;
import com.aoindustries.util.IntList;
import com.aoindustries.validation.ValidationException;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The <code>PackageHandler</code> handles all the accesses to the <code>billing.Package</code> table.
 *
 * @author  AO Industries, Inc.
 */
final public class PackageHandler {

    private PackageHandler() {
    }

    private final static Map<Account.Name,Boolean> disabledPackages=new HashMap<>();

    public static boolean canPackageAccessServer(DatabaseConnection conn, RequestSource source, Account.Name packageName, int server) throws IOException, SQLException {
        return conn.executeBooleanQuery(
            "select\n"
            + "  (\n"
            + "    select\n"
            + "      pk.id\n"
            + "    from\n"
            + "      billing.\"Package\" pk,\n"
            + "      account.\"AccountHost\" bs\n"
            + "    where\n"
            + "      pk.name=?\n"
            + "      and pk.accounting=bs.accounting\n"
            + "      and bs.server=?\n"
            + "    limit 1\n"
            + "  )\n"
            + "  is not null\n",
            packageName,
            server
        );
    }

    public static boolean canAccessPackage(DatabaseConnection conn, RequestSource source, Account.Name packageName) throws IOException, SQLException {
        return BusinessHandler.canAccessBusiness(conn, source, getBusinessForPackage(conn, packageName));
    }

    public static boolean canAccessPackage(DatabaseConnection conn, RequestSource source, int packageId) throws IOException, SQLException {
        return BusinessHandler.canAccessBusiness(conn, source, getBusinessForPackage(conn, packageId));
    }

    public static boolean canAccessPackageDefinition(DatabaseConnection conn, RequestSource source, int packageId) throws IOException, SQLException {
        return BusinessHandler.canAccessBusiness(conn, source, getBusinessForPackageDefinition(conn, packageId));
    }

    public static void checkAccessPackage(DatabaseConnection conn, RequestSource source, String action, Account.Name packageName) throws IOException, SQLException {
        if(!canAccessPackage(conn, source, packageName)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" is not allowed to access package: action='"
                +action
                +", name="
                +packageName
            ;
            throw new SQLException(message);
        }
    }

    public static void checkAccessPackage(DatabaseConnection conn, RequestSource source, String action, int packageId) throws IOException, SQLException {
        if(!canAccessPackage(conn, source, packageId)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" is not allowed to access package: action='"
                +action
                +", id="
                +packageId
            ;
            throw new SQLException(message);
        }
    }

    public static void checkAccessPackageDefinition(DatabaseConnection conn, RequestSource source, String action, int packageId) throws IOException, SQLException {
        if(!canAccessPackageDefinition(conn, source, packageId)) {
            String message=
                "business_administrator.username="
                +source.getUsername()
                +" is not allowed to access package: action='"
                +action
                +", id="
                +packageId
            ;
            throw new SQLException(message);
        }
    }

    /**
     * Creates a new <code>Package</code>.
     */
    public static int addPackage(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        Account.Name packageName,
        Account.Name accounting,
        int packageDefinition
    ) throws IOException, SQLException {
        BusinessHandler.checkAccessBusiness(conn, source, "addPackage", accounting);
        if(BusinessHandler.isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to add Package '"+packageName+"', Account disabled: "+accounting);

        // Check the PackageDefinition rules
        checkAccessPackageDefinition(conn, source, "addPackage", packageDefinition);
        // Businesses parent must be the package definition owner
        Account.Name parent=BusinessHandler.getParentBusiness(conn, accounting);
        Account.Name packageDefinitionBusiness = getBusinessForPackageDefinition(conn, packageDefinition);
        if(!packageDefinitionBusiness.equals(parent)) throw new SQLException("Unable to add Package '"+packageName+"', PackageDefinition #"+packageDefinition+" not owned by parent Account");
        if(!isPackageDefinitionApproved(conn, packageDefinition)) throw new SQLException("Unable to add Package '"+packageName+"', PackageDefinition not approved: "+packageDefinition);
        //if(!isPackageDefinitionActive(conn, packageDefinition)) throw new SQLException("Unable to add Package '"+packageName+"', PackageDefinition not active: "+packageDefinition);

        int packageId = conn.executeIntUpdate(
            "INSERT INTO\n"
            + "  billing.\"Package\"\n"
            + "VALUES (\n"
            + "  default,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  now(),\n"
            + "  ?,\n"
            + "  null,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?\n"
            + ") RETURNING id",
            packageName.toString(),
            accounting.toString(),
            packageDefinition,
            source.getUsername().toString(),
			Package.DEFAULT_EMAIL_IN_BURST,
			Package.DEFAULT_EMAIL_IN_RATE,
			Package.DEFAULT_EMAIL_OUT_BURST,
			Package.DEFAULT_EMAIL_OUT_RATE,
			Package.DEFAULT_EMAIL_RELAY_BURST,
			Package.DEFAULT_EMAIL_RELAY_RATE
		);

        // Notify all clients of the update
        invalidateList.addTable(conn, Table.TableID.PACKAGES, accounting, InvalidateList.allServers, false);

        return packageId;
    }

    /**
     * Creates a new <code>PackageDefinition</code>.
     */
    public static int addPackageDefinition(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        Account.Name accounting,
        String category,
        String name,
        String version,
        String display,
        String description,
        int setupFee,
        String setupFeeTransactionType,
        int monthlyRate,
        String monthlyRateTransactionType
    ) throws IOException, SQLException {
        BusinessHandler.checkAccessBusiness(conn, source, "addPackageDefinition", accounting);
        if(BusinessHandler.isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to add PackageDefinition, Account disabled: "+accounting);

        int packageDefinition = conn.executeIntUpdate(
            "INSERT INTO\n"
            + "  billing.\"PackageDefinition\"\n"
            + "VALUES (\n"
            + "  default,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  ?,\n"
            + "  false,\n"
            + "  false\n"
            + ") RETURNING id",
            accounting.toString(),
            category,
            name,
            version,
            display,
            description,
            setupFee <= 0 ? Null.NUMERIC : new BigDecimal(SQLUtility.getDecimal(setupFee)),
            setupFeeTransactionType,
            new BigDecimal(SQLUtility.getDecimal(monthlyRate)),
            monthlyRateTransactionType
		);

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITIONS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );

        return packageDefinition;
    }

    /**
     * Copies a <code>PackageDefinition</code>.
     */
    public static int copyPackageDefinition(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int packageDefinition
    ) throws IOException, SQLException {
        checkAccessPackageDefinition(conn, source, "copyPackageDefinition", packageDefinition);
        Account.Name accounting = getBusinessForPackageDefinition(conn, packageDefinition);
        if(BusinessHandler.isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to copy PackageDefinition, Account disabled: "+accounting);
        String category=conn.executeStringQuery("select category from billing.\"PackageDefinition\" where id=?", packageDefinition);
        String name=conn.executeStringQuery("select name from billing.\"PackageDefinition\" where id=?", packageDefinition);
        String version=conn.executeStringQuery("select version from billing.\"PackageDefinition\" where id=?", packageDefinition);
        String newVersion=null;
        for(int c=1;c<Integer.MAX_VALUE;c++) {
            String temp=version+"."+c;
            if(
                conn.executeBooleanQuery(
                    "select (select id from billing.\"PackageDefinition\" where accounting=? and category=? and name=? and version=? limit 1) is null",
                    accounting,
                    category,
                    name,
                    temp
                )
            ) {
                newVersion=temp;
                break;
            }
        }
        if(newVersion==null) throw new SQLException("Unable to generate new version for copy PackageDefinition: "+packageDefinition);

        int newPKey = conn.executeIntUpdate(
            "INSERT INTO billing.\"PackageDefinition\" (\n"
            + "  accounting,\n"
            + "  category,\n"
            + "  name,\n"
            + "  version,\n"
            + "  display,\n"
            + "  description,\n"
            + "  setup_fee,\n"
            + "  setup_fee_transaction_type,\n"
            + "  monthly_rate,\n"
            + "  monthly_rate_transaction_type\n"
            + ") SELECT\n"
            + "  accounting,\n"
            + "  category,\n"
            + "  name,\n"
            + "  ?,\n"
            + "  display,\n"
            + "  description,\n"
            + "  setup_fee,\n"
            + "  setup_fee_transaction_type,\n"
            + "  monthly_rate,\n"
            + "  monthly_rate_transaction_type\n"
            + "FROM\n"
            + "  billing.\"PackageDefinition\"\n"
            + "WHERE\n"
            + "  id=?\n"
			+ "RETURNING id",
            newVersion,
            packageDefinition
        );
        conn.executeUpdate(
            "insert into\n"
            + "  billing.\"PackageDefinitionLimit\"\n"
            + "(\n"
            + "  package_definition,\n"
            + "  resource,\n"
            + "  soft_limit,\n"
            + "  hard_limit,\n"
            + "  additional_rate,\n"
            + "  additional_transaction_type\n"
            + ") select\n"
            + "  ?,\n"
            + "  resource,\n"
            + "  soft_limit,\n"
            + "  hard_limit,\n"
            + "  additional_rate,\n"
            + "  additional_transaction_type\n"
            + "from\n"
            + "  billing.\"PackageDefinitionLimit\"\n"
            + "where\n"
            + "  package_definition=?",
            newPKey,
            packageDefinition
        );

        // Notify all clients of the update
        IntList servers=BusinessHandler.getServersForBusiness(conn, accounting);
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITIONS,
            accounting,
            servers,
            false
        );
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITION_LIMITS,
            accounting,
            servers,
            false
        );

        return newPKey;
    }

    public static void updatePackageDefinition(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int packageDefinition,
        Account.Name accounting,
        String category,
        String name,
        String version,
        String display,
        String description,
        int setupFee,
        String setupFeeTransactionType,
        int monthlyRate,
        String monthlyRateTransactionType
    ) throws IOException, SQLException {
        // Security checks
        checkAccessPackageDefinition(conn, source, "updatePackageDefinition", packageDefinition);
        BusinessHandler.checkAccessBusiness(conn, source, "updatePackageDefinition", accounting);
        if(isPackageDefinitionApproved(conn, packageDefinition)) throw new SQLException("Not allowed to update an approved PackageDefinition: "+packageDefinition);

        PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false).prepareStatement(
            "update\n"
            + "  billing.\"PackageDefinition\"\n"
            + "set\n"
            + "  accounting=?,\n"
            + "  category=?,\n"
            + "  name=?,\n"
            + "  version=?,\n"
            + "  display=?,\n"
            + "  description=?,\n"
            + "  setup_fee=?,\n"
            + "  setup_fee_transaction_type=?,\n"
            + "  monthly_rate=?,\n"
            + "  monthly_rate_transaction_type=?\n"
            + "where\n"
            + "  id=?"
        );
        try {
            pstmt.setString(1, accounting.toString());
            pstmt.setString(2, category);
            pstmt.setString(3, name);
            pstmt.setString(4, version);
            pstmt.setString(5, display);
            pstmt.setString(6, description);
            pstmt.setBigDecimal(7, setupFee<=0 ? null : new BigDecimal(SQLUtility.getDecimal(setupFee)));
            pstmt.setString(8, setupFeeTransactionType);
            pstmt.setBigDecimal(9, new BigDecimal(SQLUtility.getDecimal(monthlyRate)));
            pstmt.setString(10, monthlyRateTransactionType);
            pstmt.setInt(11, packageDefinition);
            pstmt.executeUpdate();
        } catch(SQLException err) {
            throw new WrappedSQLException(err, pstmt);
        } finally {
            pstmt.close();
        }

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITIONS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static void disablePackage(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int disableLog,
        Account.Name name
    ) throws IOException, SQLException {
        BusinessHandler.checkAccessDisableLog(conn, source, "disablePackage", disableLog, false);
        checkAccessPackage(conn, source, "disablePackage", name);
        if(isPackageDisabled(conn, name)) throw new SQLException("Package is already disabled: "+name);
        IntList hsts=HttpdHandler.getHttpdSharedTomcatsForPackage(conn, name);
        for(int c=0;c<hsts.size();c++) {
            int hst=hsts.getInt(c);
            if(!HttpdHandler.isHttpdSharedTomcatDisabled(conn, hst)) {
                throw new SQLException("Cannot disable Package '"+name+"': SharedTomcat not disabled: "+hst);
            }
        }
        IntList eps=EmailHandler.getEmailPipesForPackage(conn, name);
        for(int c=0;c<eps.size();c++) {
            int ep=eps.getInt(c);
            if(!EmailHandler.isEmailPipeDisabled(conn, ep)) {
                throw new SQLException("Cannot disable Package '"+name+"': Pipe not disabled: "+ep);
            }
        }
        List<com.aoindustries.aoserv.client.account.User.Name> uns=UsernameHandler.getUsernamesForPackage(conn, name);
		for (com.aoindustries.aoserv.client.account.User.Name username : uns) {
            if(!UsernameHandler.isUsernameDisabled(conn, username)) {
                throw new SQLException("Cannot disable Package '"+name+"': Username not disabled: "+username);
            }
        }
        IntList hss=HttpdHandler.getHttpdSitesForPackage(conn, name);
        for(int c=0;c<hss.size();c++) {
            int hs=hss.getInt(c);
            if(!HttpdHandler.isHttpdSiteDisabled(conn, hs)) {
                throw new SQLException("Cannot disable Package '"+name+"': Site not disabled: "+hs);
            }
        }
        IntList els=EmailHandler.getEmailListsForPackage(conn, name);
        for(int c=0;c<els.size();c++) {
            int el=els.getInt(c);
            if(!EmailHandler.isEmailListDisabled(conn, el)) {
                throw new SQLException("Cannot disable Package '"+name+"': List not disabled: "+el);
            }
        }
        IntList ssrs=EmailHandler.getEmailSmtpRelaysForPackage(conn, name);
        for(int c=0;c<ssrs.size();c++) {
            int ssr=ssrs.getInt(c);
            if(!EmailHandler.isEmailSmtpRelayDisabled(conn, ssr)) {
                throw new SQLException("Cannot disable Package '"+name+"': SmtpRelay not disabled: "+ssr);
            }
        }

        conn.executeUpdate(
            "update billing.\"Package\" set disable_log=? where name=?",
            disableLog,
            name
        );

        // Notify all clients of the update
        Account.Name accounting = getBusinessForPackage(conn, name);
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGES,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static void enablePackage(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        Account.Name name
    ) throws IOException, SQLException {
        checkAccessPackage(conn, source, "enablePackage", name);
        int disableLog=getDisableLogForPackage(conn, name);
        if(disableLog==-1) throw new SQLException("Package is already enabled: "+name);
        BusinessHandler.checkAccessDisableLog(conn, source, "enablePackage", disableLog, true);
        Account.Name accounting = getBusinessForPackage(conn, name);
        if(BusinessHandler.isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to enable Package '"+name+"', Account not enabled: "+accounting);

        conn.executeUpdate(
            "update billing.\"Package\" set disable_log=null where name=?",
            name
        );

        // Notify all clients of the update
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGES,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static Account.Name generatePackageName(
        DatabaseConnection conn,
        Account.Name template
    ) throws IOException, SQLException {
		// Load the entire list of package names
		Set<Account.Name> names = conn.executeObjectCollectionQuery(new HashSet<>(),
			ObjectFactories.accountNameFactory,
			"select name from billing.\"Package\""
		);
		// Find one that is not used
		for(int c=0;c<Integer.MAX_VALUE;c++) {
			Account.Name name;
			try {
				name = Account.Name.valueOf(template.toString()+c);
			} catch(ValidationException e) {
				throw new SQLException(e);
			}
			if(!names.contains(name)) return name;
		}
		// If could not find one, report and error
        throw new SQLException("Unable to find available package name for template: "+template);
    }

    public static int getDisableLogForPackage(DatabaseConnection conn, Account.Name name) throws IOException, SQLException {
        return conn.executeIntQuery("select coalesce(disable_log, -1) from billing.\"Package\" where name=?", name);
    }

    public static List<String> getPackages(
        DatabaseConnection conn,
        RequestSource source
    ) throws IOException, SQLException {
        com.aoindustries.aoserv.client.account.User.Name username=source.getUsername();
        User masterUser=MasterServer.getUser(conn, username);
        UserHost[] masterServers=masterUser==null?null:MasterServer.getUserHosts(conn, source.getUsername());
        if(masterUser!=null) {
            if(masterServers.length==0) return conn.executeStringListQuery("select name from billing.\"Package\"");
            else return conn.executeStringListQuery(
                "select\n"
                + "  pk.name\n"
                + "from\n"
                + "  master.\"UserHost\" ms,\n"
                + "  account.\"AccountHost\" bs,\n"
                + "  billing.\"Package\" pk\n"
                + "where\n"
                + "  ms.username=?\n"
                + "  and ms.server=bs.server\n"
                + "  and bs.accounting=pk.accounting\n"
                + "group by\n"
                + "  pk.name",
                username
            );
        } else return conn.executeStringListQuery(
            "select\n"
            + "  pk2.name\n"
            + "from\n"
            + "  account.\"User\" un,\n"
            + "  billing.\"Package\" pk1,\n"
            + TableHandler.BU1_PARENTS_JOIN
            + "  billing.\"Package\" pk2\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and un.package=pk1.name\n"
            + "  and (\n"
            + TableHandler.PK1_BU1_PARENTS_WHERE
            + "  )\n"
            + "  and bu1.accounting=pk2.accounting",
            username
        );
    }

    public static IntList getIntPackages(
        DatabaseConnection conn,
        RequestSource source
    ) throws IOException, SQLException {
        com.aoindustries.aoserv.client.account.User.Name username=source.getUsername();
        User masterUser=MasterServer.getUser(conn, username);
        UserHost[] masterServers=masterUser==null?null:MasterServer.getUserHosts(conn, source.getUsername());
        if(masterUser!=null) {
            if(masterServers.length==0) return conn.executeIntListQuery("select id from billing.\"Package\"");
            else return conn.executeIntListQuery(
                "select\n"
                + "  pk.id\n"
                + "from\n"
                + "  master.\"UserHost\" ms,\n"
                + "  account.\"AccountHost\" bs,\n"
                + "  billing.\"Package\" pk\n"
                + "where\n"
                + "  ms.username=?\n"
                + "  and ms.server=bs.server\n"
                + "  and bs.accounting=pk.accounting\n"
                + "group by\n"
                + "  pk.id",
                username
            );
        } else return conn.executeIntListQuery(
            "select\n"
            + "  pk2.id\n"
            + "from\n"
            + "  account.\"User\" un,\n"
            + "  billing.\"Package\" pk1,\n"
            + TableHandler.BU1_PARENTS_JOIN
            + "  billing.\"Package\" pk2\n"
            + "where\n"
            + "  un.username=?\n"
            + "  and un.package=pk1.name\n"
            + "  and (\n"
            + TableHandler.PK1_BU1_PARENTS_WHERE
            + "  )\n"
            + "  and bu1.accounting=pk2.accounting",
            username
        );
    }

    public static void invalidateTable(Table.TableID tableID) {
        if(tableID==Table.TableID.PACKAGES) {
            synchronized(PackageHandler.class) {
                disabledPackages.clear();
            }
            synchronized(packageBusinesses) {
                packageBusinesses.clear();
            }
            synchronized(packageNames) {
                packageNames.clear();
            }
            synchronized(packagePKeys) {
                packagePKeys.clear();
            }
        }
    }

    public static boolean isPackageDisabled(DatabaseConnection conn, Account.Name name) throws IOException, SQLException {
	    synchronized(PackageHandler.class) {
            Boolean O=disabledPackages.get(name);
            if(O!=null) return O;
            boolean isDisabled=getDisableLogForPackage(conn, name)!=-1;
            disabledPackages.put(name, isDisabled);
            return isDisabled;
	    }
    }

    public static boolean isPackageNameAvailable(DatabaseConnection conn, Account.Name packageName) throws IOException, SQLException {
        return conn.executeBooleanQuery("select (select id from billing.\"Package\" where name=? limit 1) is null", packageName);
    }

    public static int findActivePackageDefinition(DatabaseConnection conn, Account.Name accounting, int rate, int userLimit, int popLimit) throws IOException, SQLException {
        return conn.executeIntQuery(
            "select\n"
            + "  coalesce(\n"
            + "    (\n"
            + "      select\n"
            + "        pd.id\n"
            + "      from\n"
            + "        billing.\"PackageDefinition\" pd,\n"
            + "        package_definitions_limits user_pdl,\n"
            + "        package_definitions_limits pop_pdl\n"
            + "      where\n"
            + "        pd.accounting=?\n"
            + "        and pd.monthly_rate=?\n"
            + "        and pd.id=user_pdl.package_definition\n"
            + "        and user_pdl.resource=?\n"
            + "        and pd.id=pop_pdl.package_definition\n"
            + "        and pop_pdl.resource=?\n"
            + "      limit 1\n"
            + "    ), -1\n"
            + "  )",
            accounting,
            SQLUtility.getDecimal(rate),
            Resource.USER,
            Resource.EMAIL
        );
    }

    public static boolean isPackageDefinitionApproved(DatabaseConnection conn, int packageDefinition) throws IOException, SQLException {
        return conn.executeBooleanQuery("select approved from billing.\"PackageDefinition\" where id=?", packageDefinition);
    }

    public static boolean isPackageDefinitionActive(DatabaseConnection conn, int packageDefinition) throws IOException, SQLException {
        return conn.executeBooleanQuery("select active from billing.\"PackageDefinition\" where id=?", packageDefinition);
    }

    public static void checkPackageAccessServer(DatabaseConnection conn, RequestSource source, String action, Account.Name packageName, int server) throws IOException, SQLException {
        if(!canPackageAccessServer(conn, source, packageName, server)) {
            String message=
                "package.name="
                +packageName
                +" is not allowed to access server.id="
                +server
                +": action='"
                +action
                +"'"
            ;
            throw new SQLException(message);
        }
    }

    public static Account.Name getBusinessForPackage(DatabaseAccess database, Account.Name packageName) throws IOException, SQLException {
        return getBusinessForPackage(database, getPKeyForPackage(database, packageName));
    }

    private static final Map<Integer,Account.Name> packageBusinesses=new HashMap<>();
    public static Account.Name getBusinessForPackage(DatabaseAccess database, int packageId) throws IOException, SQLException {
        Integer I = packageId;
        synchronized(packageBusinesses) {
            Account.Name O=packageBusinesses.get(I);
            if(O!=null) return O;
            Account.Name business = database.executeObjectQuery(ObjectFactories.accountNameFactory,
                "select accounting from billing.\"Package\" where id=?",
                packageId
            );
            packageBusinesses.put(I, business);
            return business;
        }
    }

    private static final Map<Integer,Account.Name> packageNames=new HashMap<>();
    public static Account.Name getNameForPackage(DatabaseConnection conn, int packageId) throws IOException, SQLException {
        Integer I = packageId;
        synchronized(packageNames) {
            Account.Name O=packageNames.get(I);
            if(O!=null) return O;
            Account.Name name = conn.executeObjectQuery(ObjectFactories.accountNameFactory,
				"select name from billing.\"Package\" where id=?",
				packageId
			);
            packageNames.put(I, name);
            return name;
        }
    }

    private static final Map<Account.Name,Integer> packagePKeys=new HashMap<>();
    public static int getPKeyForPackage(DatabaseAccess database, Account.Name name) throws IOException, SQLException {
        synchronized(packagePKeys) {
            Integer O=packagePKeys.get(name);
            if(O!=null) return O;
            int packageId = database.executeIntQuery("select id from billing.\"Package\" where name=?", name);
            packagePKeys.put(name, packageId);
            return packageId;
        }
    }

    public static Account.Name getBusinessForPackageDefinition(DatabaseConnection conn, int packageId) throws IOException, SQLException {
        return conn.executeObjectQuery(ObjectFactories.accountNameFactory,
            "select accounting from billing.\"PackageDefinition\" where id=?",
            packageId
        );
    }

    public static List<Account.Name> getBusinessesForPackageDefinition(DatabaseConnection conn, int packageDefinition) throws IOException, SQLException {
        return conn.executeObjectCollectionQuery(new ArrayList<Account.Name>(),
            ObjectFactories.accountNameFactory,
            "select distinct\n"
            + "  bu.accounting\n"
            + "from\n"
            + "  billing.\"Package\" pk,\n"
            + "  account.\"Account\" bu\n"
            + "where\n"
            + "  pk.package_definition=?\n"
            + "  and pk.accounting=bu.accounting",
            packageDefinition
        );
    }

    public static void setPackageDefinitionActive(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int packageDefinition,
        boolean isActive
    ) throws IOException, SQLException {
        checkAccessPackageDefinition(conn, source, "setPackageDefinitionActive", packageDefinition);
        // Must be approved to be activated
        if(isActive && !isPackageDefinitionApproved(conn, packageDefinition)) throw new SQLException("PackageDefinition must be approved before it may be activated: "+packageDefinition);

        // Update the database
        conn.executeUpdate(
            "update billing.\"PackageDefinition\" set active=? where id=?",
            isActive,
            packageDefinition
        );

        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITIONS,
            getBusinessForPackageDefinition(conn, packageDefinition),
            InvalidateList.allServers,
            false
        );
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITIONS,
            getBusinessesForPackageDefinition(conn, packageDefinition),
            InvalidateList.allServers,
            false
        );
    }

    public static void setPackageDefinitionLimits(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int packageDefinition,
        String[] resources,
        int[] soft_limits,
        int[] hard_limits,
        int[] additional_rates,
        String[] additional_transaction_types
    ) throws IOException, SQLException {
        checkAccessPackageDefinition(conn, source, "setPackageDefinitionLimits", packageDefinition);
        // Must not be approved to be edited
        if(isPackageDefinitionApproved(conn, packageDefinition)) throw new SQLException("PackageDefinition may not have its limits set after it is approved: "+packageDefinition);

        // Update the database
        conn.executeUpdate("delete from billing.\"PackageDefinitionLimit\" where package_definition=?", packageDefinition);
        for(int c=0;c<resources.length;c++) {
            conn.executeUpdate(
                "insert into\n"
                + "  billing.\"PackageDefinitionLimit\"\n"
                + "(\n"
                + "  package_definition,\n"
                + "  resource,\n"
                + "  soft_limit,\n"
                + "  hard_limit,\n"
                + "  additional_rate,\n"
                + "  additional_transaction_type\n"
                + ") values(\n"
                + "  ?,\n"
                + "  ?,\n"
                + "  ?::integer,\n"
                + "  ?::integer,\n"
                + "  ?::numeric(9,2),\n"
                + "  ?\n"
                + ")",
                packageDefinition,
                resources[c],
                soft_limits[c]==-1 ? null : Integer.toString(soft_limits[c]),
                hard_limits[c]==-1 ? null : Integer.toString(hard_limits[c]),
                additional_rates[c]<=0 ? null : SQLUtility.getDecimal(additional_rates[c]),
                additional_transaction_types[c]
            );
        }

        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITION_LIMITS,
            getBusinessForPackageDefinition(conn, packageDefinition),
            InvalidateList.allServers,
            false
        );
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITION_LIMITS,
            getBusinessesForPackageDefinition(conn, packageDefinition),
            InvalidateList.allServers,
            false
        );
    }

    public static void removePackageDefinition(
        DatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int packageDefinition
    ) throws IOException, SQLException {
        // Security checks
        PackageHandler.checkAccessPackageDefinition(conn, source, "removePackageDefinition", packageDefinition);

        // Do the remove
        removePackageDefinition(conn, invalidateList, packageDefinition);
    }

    public static void removePackageDefinition(
        DatabaseConnection conn,
        InvalidateList invalidateList,
        int id
    ) throws IOException, SQLException {
        Account.Name accounting = getBusinessForPackageDefinition(conn, id);
        IntList servers=BusinessHandler.getServersForBusiness(conn, accounting);
        if(conn.executeUpdate("delete from billing.\"PackageDefinitionLimit\" where package_definition=?", id)>0) {
            invalidateList.addTable(
                conn,
                Table.TableID.PACKAGE_DEFINITION_LIMITS,
                accounting,
                servers,
                false
            );
        }

        conn.executeUpdate("delete from billing.\"PackageDefinition\" where id=?", id);
        invalidateList.addTable(
            conn,
            Table.TableID.PACKAGE_DEFINITIONS,
            accounting,
            servers,
            false
        );
    }
}