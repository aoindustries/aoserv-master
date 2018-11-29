/*
 * Copyright 2001-2013, 2015, 2017, 2018 by AO Industries, Inc.,
 * 7262 Bull Pen Cir, Mobile, Alabama, 36695, U.S.A.
 * All rights reserved.
 */
package com.aoindustries.aoserv.master;

import com.aoindustries.aoserv.client.AOServPermission;
import com.aoindustries.aoserv.client.Business;
import com.aoindustries.aoserv.client.BusinessAdministrator;
import com.aoindustries.aoserv.client.CountryCode;
import com.aoindustries.aoserv.client.LinuxAccount;
import com.aoindustries.aoserv.client.MasterUser;
import com.aoindustries.aoserv.client.NoticeLog;
import com.aoindustries.aoserv.client.PasswordChecker;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.client.validator.AccountingCode;
import com.aoindustries.aoserv.client.validator.HashedPassword;
import com.aoindustries.aoserv.client.validator.UserId;
import com.aoindustries.dbc.DatabaseAccess.Null;
import com.aoindustries.dbc.DatabaseConnection;
import com.aoindustries.net.Email;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import com.aoindustries.util.StringUtility;
import com.aoindustries.validation.ValidationException;
import com.aoindustries.validation.ValidationResult;
import java.io.IOException;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * The <code>BusinessHandler</code> handles all the accesses to the Business tables.
 *
 * @author  AO Industries, Inc.
 */
final public class BusinessHandler {

	private BusinessHandler() {
	}

	private static final Object businessAdministratorsLock=new Object();
	private static Map<UserId,BusinessAdministrator> businessAdministrators;

	private static final Object usernameBusinessesLock=new Object();
	private static Map<UserId,List<AccountingCode>> usernameBusinesses;
	private final static Map<UserId,Boolean> disabledBusinessAdministrators=new HashMap<>();
	private final static Map<AccountingCode,Boolean> disabledBusinesses=new HashMap<>();

	public static boolean canAccessBusiness(DatabaseConnection conn, RequestSource source, AccountingCode accounting) throws IOException, SQLException {
		//UserId username=source.getUsername();
		return
			getAllowedBusinesses(conn, source)
			.contains(
				accounting //UsernameHandler.getBusinessForUsername(conn, username)
			)
		;
	}

	public static boolean canAccessDisableLog(DatabaseConnection conn, RequestSource source, int id, boolean enabling) throws IOException, SQLException {
		UserId username=source.getUsername();
		UserId disabledBy=getDisableLogDisabledBy(conn, id);
		if(enabling) {
			AccountingCode baAccounting = UsernameHandler.getBusinessForUsername(conn, username);
			AccountingCode dlAccounting = UsernameHandler.getBusinessForUsername(conn, disabledBy);
			return isBusinessOrParent(conn, baAccounting, dlAccounting);
		} else {
			return username.equals(disabledBy);
		}
	}

	public static void cancelBusiness(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting,
		String cancelReason
	) throws IOException, SQLException {
		// Check permissions
		checkPermission(conn, source, "cancelBusiness", AOServPermission.Permission.cancel_business);

		// Check access to business
		checkAccessBusiness(conn, source, "cancelBusiness", accounting);

		if(accounting.equals(getRootBusiness())) throw new SQLException("Not allowed to cancel the root business: "+accounting);

		// Business must be disabled
		if(!isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to cancel Business, Business not disabled: "+accounting);

		// Business must not already be canceled
		if(isBusinessCanceled(conn, accounting)) throw new SQLException("Unable to cancel Business, Business already canceled: "+accounting);

		// Update the database
		conn.executeUpdate(
			"update account.\"Account\" set canceled=now(), cancel_reason=? where accounting=?",
			cancelReason,
			accounting
		);

		// Notify the clients
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, getServersForBusiness(conn, accounting), false);
	}

	public static boolean canBusinessServer(
		DatabaseConnection conn,
		RequestSource source,
		int server,
		String column
	) throws IOException, SQLException {
		return conn.executeBooleanQuery(
			"select\n"
			+ "  bs."+column+"\n"
			+ "from\n"
			+ "  account.\"Username\" un,\n"
			+ "  billing.\"Package\" pk,\n"
			+ "  account.\"AccountHost\" bs\n"
			+ "where\n"
			+ "  un.username=?\n"
			+ "  and un.package=pk.name\n"
			+ "  and pk.accounting=bs.accounting\n"
			+ "  and bs.server=?",
			source.getUsername(),
			server
		);
	}

	public static void checkAccessBusiness(DatabaseConnection conn, RequestSource source, String action, AccountingCode accounting) throws IOException, SQLException {
		if(!canAccessBusiness(conn, source, accounting)) {
			String message=
			"business_administrator.username="
			+source.getUsername()
			+" is not allowed to access business: action='"
			+action
			+"', accounting="
			+accounting
			;
			throw new SQLException(message);
		}
	}

	public static void checkAccessDisableLog(DatabaseConnection conn, RequestSource source, String action, int id, boolean enabling) throws IOException, SQLException {
		if(!canAccessDisableLog(conn, source, id, enabling)) {
			String message=
				"business_administrator.username="
				+source.getUsername()
				+" is not allowed to access account.DisableLog: action='"
				+action
				+"', id="
				+id
			;
			throw new SQLException(message);
		}
	}

	public static void checkAddBusiness(DatabaseConnection conn, RequestSource source, String action, AccountingCode parent, int server) throws IOException, SQLException {
		boolean canAdd = conn.executeBooleanQuery("select can_add_businesses from account.\"Account\" where accounting=?", UsernameHandler.getBusinessForUsername(conn, source.getUsername()));
		if(canAdd) {
			MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
			if(mu!=null) {
				if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) canAdd = false;
			} else {
				canAdd =
					canAccessBusiness(conn, source, parent)
					&& ServerHandler.canAccessServer(conn, source, server)
				;
			}
		}
		if(!canAdd) {
			String message=
			"business_administrator.username="
			+source.getUsername()
			+" is not allowed to add business: action='"
			+action
			+"', parent="
			+parent
			+", server="
			+server
			;
			throw new SQLException(message);
		}
	}

	private static Map<UserId,Set<String>> cachedPermissions;
	private static final Object cachedPermissionsLock = new Object();

	public static boolean hasPermission(DatabaseConnection conn, RequestSource source, AOServPermission.Permission permission) throws IOException, SQLException {
		synchronized(cachedPermissionsLock) {
			if(cachedPermissions == null) {
				cachedPermissions = conn.executeQuery(
					(ResultSet results) -> {
						Map<UserId,Set<String>> newCache = new HashMap<>();
						while(results.next()) {
							UserId username;
							try {
								username = UserId.valueOf(results.getString(1));
							} catch(ValidationException e) {
								throw new SQLException(e);
							}
							Set<String> permissions = newCache.get(username);
							if(permissions==null) newCache.put(username, permissions = new HashSet<>());
							permissions.add(results.getString(2));
						}
						return newCache;
					},
					"select username, permission from master.\"AdministratorPermission\""
				);
			}
			Set<String> permissions = cachedPermissions.get(source.getUsername());
			return permissions!=null && permissions.contains(permission.name());
		}
	}

	public static void checkPermission(DatabaseConnection conn, RequestSource source, String action, AOServPermission.Permission permission) throws IOException, SQLException {
		if(!hasPermission(conn, source, permission)) {
			String message=
				"business_administrator.username="
				+source.getUsername()
				+" does not have the \""+permission.name()+"\" permission.  Not allowed to make the following call: "
				+action
			;
			throw new SQLException(message);
		}
	}

	public static List<AccountingCode> getAllowedBusinesses(DatabaseConnection conn, RequestSource source) throws IOException, SQLException {
		synchronized(usernameBusinessesLock) {
			UserId username=source.getUsername();
			if(usernameBusinesses==null) usernameBusinesses=new HashMap<>();
			List<AccountingCode> SV=usernameBusinesses.get(username);
			if(SV==null) {
				List<AccountingCode> V;
				MasterUser mu = MasterServer.getMasterUser(conn, source.getUsername());
				if(mu!=null) {
					if(MasterServer.getMasterServers(conn, source.getUsername()).length!=0) {
						V=conn.executeObjectCollectionQuery(
							new ArrayList<AccountingCode>(),
							ObjectFactories.accountingCodeFactory,
							"select distinct\n"
							+ "  bu.accounting\n"
							+ "from\n"
							+ "  master.\"UserHost\" ms,\n"
							+ "  account.\"AccountHost\" bs,\n"
							+ "  account.\"Account\" bu\n"
							+ "where\n"
							+ "  ms.username=?\n"
							+ "  and ms.server=bs.server\n"
							+ "  and bs.accounting=bu.accounting",
							username
						);
					} else {
						V=conn.executeObjectCollectionQuery(
							new ArrayList<AccountingCode>(),
							ObjectFactories.accountingCodeFactory,
							"select accounting from account.\"Account\""
						);
					}
				} else {
					V=conn.executeObjectCollectionQuery(
						new ArrayList<AccountingCode>(),
						ObjectFactories.accountingCodeFactory,
						"select\n"
						+ "  bu1.accounting\n"
						+ "from\n"
						+ "  account.\"Username\" un,\n"
						+ "  billing.\"Package\" pk,\n"
						+ TableHandler.BU1_PARENTS_JOIN_NO_COMMA
						+ "where\n"
						+ "  un.username=?\n"
						+ "  and un.package=pk.name\n"
						+ "  and (\n"
						+ TableHandler.PK_BU1_PARENTS_WHERE
						+ "  )",
						username
					);
				}

				int size=V.size();
				SV=new SortedArrayList<>();
				for(int c=0;c<size;c++) SV.add(V.get(c));
				usernameBusinesses.put(username, SV);
			}
			return SV;
		}
	}

	public static AccountingCode getBusinessForDisableLog(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeObjectQuery(ObjectFactories.accountingCodeFactory, "select accounting from account.\"DisableLog\" where id=?", id);
	}

	/**
	 * Creates a new <code>Business</code>.
	 */
	public static void addBusiness(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting,
		String contractVersion,
		int defaultServer,
		AccountingCode parent,
		boolean can_add_backup_servers,
		boolean can_add_businesses,
		boolean can_see_prices,
		boolean billParent
	) throws IOException, SQLException {
		checkAddBusiness(conn, source, "addBusiness", parent, defaultServer);

		if(isBusinessDisabled(conn, parent)) throw new SQLException("Unable to add Business '"+accounting+"', parent is disabled: "+parent);

		// Must not exceed the maximum business tree depth
		int newDepth=getDepthInBusinessTree(conn, parent)+1;
		if(newDepth>Business.MAXIMUM_BUSINESS_TREE_DEPTH) throw new SQLException("Unable to add Business '"+accounting+"', the maximum depth of the business tree ("+Business.MAXIMUM_BUSINESS_TREE_DEPTH+") would be exceeded.");

		conn.executeUpdate(
			"insert into account.\"Account\" (\n"
			+ "  accounting,\n"
			+ "  contract_version,\n"
			+ "  parent,\n"
			+ "  can_add_backup_server,\n"
			+ "  can_add_businesses,\n"
			+ "  can_see_prices,\n"
			+ "  auto_enable,\n"
			+ "  bill_parent\n"
			+ ") values(\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  true,\n"
			+ "  ?\n"
			+ ")",
			accounting,
			contractVersion,
			parent,
			can_add_backup_servers,
			can_add_businesses,
			can_see_prices,
			billParent
		);
		conn.executeUpdate(
			"insert into account.\"AccountHost\"\n"
			+ "  accounting,\n"
			+ "  server,\n"
			+ "  is_default,\n"
			+ "  can_control_apache,\n"
			+ "  can_control_cron,\n"
			+ "  can_control_mysql,\n"
			+ "  can_control_postgresql,\n"
			+ "  can_control_xfs,\n"
			+ "  can_control_xvfb,\n"
			+ "  can_vnc_console,\n"
			+ "  can_control_virtual_server\n"
			+ ") values(\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  true,\n"
			+ "  false,\n"
			+ "  false,\n"
			+ "  false,\n"
			+ "  false,\n"
			+ "  false,\n"
			+ "  false,\n"
			+ "  false,\n"
			+ "  false\n"
			+ ")",
			accounting,
			defaultServer
		);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.VIRTUAL_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.NET_DEVICES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.IP_ADDRESSES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
	}

	/**
	 * Creates a new <code>BusinessAdministrator</code>.
	 */
	public static void addBusinessAdministrator(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		UserId username,
		String name,
		String title,
		Date birthday,
		boolean isPrivate,
		String workPhone,
		String homePhone,
		String cellPhone,
		String fax,
		String email,
		String address1,
		String address2,
		String city,
		String state,
		String country,
		String zip,
		boolean enableEmailSupport
	) throws IOException, SQLException {
		UsernameHandler.checkAccessUsername(conn, source, "addBusinessAdministrator", username);
		if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to add BusinessAdministrator named mail");
		if (country!=null && country.equals(CountryCode.US)) state=convertUSState(conn, state);

		String supportCode = enableEmailSupport ? generateSupportCode(conn) : null;
		conn.executeUpdate(
			"insert into account.\"Administrator\" values(?,null,?,?,?,false,?,now(),?,?,?,?,?,?,?,?,?,?,?,null,true,?)",
			username.toString(),
			name,
			title,
			birthday == null ? Null.DATE : birthday,
			isPrivate,
			workPhone,
			homePhone,
			cellPhone,
			fax,
			email,
			address1,
			address2,
			city,
			state,
			country,
			zip,
			supportCode
		);

		// administrators default to having the same permissions as the person who created them
		conn.executeUpdate(
			"insert into master.\"AdministratorPermission\" (username, permission) select ?, permission from master.\"AdministratorPermission\" where username=?",
			username,
			source.getUsername()
		);

		AccountingCode accounting=UsernameHandler.getBusinessForUsername(conn, username);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATOR_PERMISSIONS, accounting, InvalidateList.allServers, false);
	}

	public static String convertUSState(DatabaseConnection conn, String state) throws IOException, SQLException {
		String newState = conn.executeStringQuery(
			"select coalesce((select code from account.\"UsState\" where upper(name)=upper(?) or code=upper(?)),'')",
			state,
			state
		);
		if(newState.length()==0) {
			throw new SQLException(
				state==null || state.length()==0
				?"State required for the United States"
				:"Invalid US state: "+state
			);
		}
		return newState;
	}

	/**
	 * Creates a new <code>BusinessProfile</code>.
	 */
	public static int addBusinessProfile(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting,
		String name,
		boolean isPrivate,
		String phone,
		String fax,
		String address1,
		String address2,
		String city,
		String state,
		String country,
		String zip,
		boolean sendInvoice,
		String billingContact,
		String billingEmail,
		String technicalContact,
		String technicalEmail
	) throws IOException, SQLException {
		checkAccessBusiness(conn, source, "createBusinessProfile", accounting);

		if (country.equals(CountryCode.US)) state=convertUSState(conn, state);

		int priority=conn.executeIntQuery("select coalesce(max(priority)+1, 1) from account.\"Profile\" where accounting=?", accounting);

		int id = conn.executeIntUpdate(
			"INSERT INTO account.\"Profile\" VALUES (default,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?,?,?) RETURNING id",
			accounting.toString(),
			priority,
			name,
			isPrivate,
			phone,
			fax,
			address1,
			address2,
			city,
			state,
			country,
			zip,
			sendInvoice,
			billingContact,
			billingEmail,
			technicalContact,
			technicalEmail
		);
		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_PROFILES, accounting, InvalidateList.allServers, false);
		return id;
	}

	/**
	 * Creates a new <code>BusinessServer</code>.
	 */
	public static int addBusinessServer(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting,
		int server
	) throws IOException, SQLException {
		// Must be allowed to access the Business
		checkAccessBusiness(conn, source, "addBusinessServer", accounting);
		if(!accounting.equals(getRootBusiness())) ServerHandler.checkAccessServer(conn, source, "addBusinessServer", server);

		return addBusinessServer(conn, invalidateList, accounting, server);
	}

	/**
	 * Creates a new <code>BusinessServer</code>.
	 */
	public static int addBusinessServer(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		AccountingCode accounting,
		int server
	) throws IOException, SQLException {
		if(isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to add BusinessServer, Business disabled: "+accounting);

		// Parent business must also have access to the server
		if(
			!accounting.equals(getRootBusiness())
			&& conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      bs.id\n"
				+ "    from\n"
				+ "      account.\"Account\" bu,\n"
				+ "      account.\"AccountHost\" bs\n"
				+ "    where\n"
				+ "      bu.accounting=?\n"
				+ "      and bu.parent=bs.accounting\n"
				+ "      and bs.server=?\n"
				+ "  ) is null",
				accounting,
				server
			)
		) throw new SQLException("Unable to add business_server, parent does not have access to server.  accounting="+accounting+", server="+server);

		boolean hasDefault=conn.executeBooleanQuery("select (select id from account.\"AccountHost\" where accounting=? and is_default limit 1) is not null", accounting);

		int id = conn.executeIntUpdate(
			"INSERT INTO account.\"AccountHost\" (accounting, server, is_default) VALUES (?,?,?) RETURNING id",
			accounting,
			server,
			!hasDefault
		);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.VIRTUAL_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.NET_DEVICES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.IP_ADDRESSES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		return id;
	}

	/**
	 * Creates a new <code>DistroLog</code>.
	 */
	public static int addDisableLog(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting,
		String disableReason
	) throws IOException, SQLException {
		checkAccessBusiness(conn, source, "addDisableLog", accounting);

		UserId username=source.getUsername();
		int id = conn.executeIntUpdate(
			"INSERT INTO account.\"DisableLog\" (accounting, disabled_by, disable_reason) VALUES (?,?,?) RETURNING id",
			accounting,
			username,
			disableReason
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.DISABLE_LOG,
			accounting,
			InvalidateList.allServers,
			false
		);
		return id;
	}

	/**
	 * Adds a notice log.
	 */
	public static void addNoticeLog(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting,
		String billingContact,
		String emailAddress,
		int balance,
		String type,
		int transid
	) throws IOException, SQLException {
		checkAccessBusiness(conn, source, "addNoticeLog", accounting);
		if(transid!=NoticeLog.NO_TRANSACTION) TransactionHandler.checkAccessTransaction(conn, source, "addNoticeLog", transid);

		conn.executeUpdate(
			"insert into\n"
			+ "  billing.\"NoticeLog\"\n"
			+ "(\n"
			+ "  accounting,\n"
			+ "  billing_contact,\n"
			+ "  billing_email,\n"
			+ "  balance,\n"
			+ "  notice_type,\n"
			+ "  transid\n"
			+ ") values(\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?,\n"
			+ "  ?::numeric(9,2),\n"
			+ "  ?,\n"
			+ "  ?\n"
			+ ")",
			accounting.toString(),
			billingContact,
			emailAddress,
			SQLUtility.getDecimal(balance),
			type,
			(transid == NoticeLog.NO_TRANSACTION) ? Null.INTEGER : transid
		);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.NOTICE_LOG, accounting, InvalidateList.allServers, false);
	}

	public static void disableBusiness(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int disableLog,
		AccountingCode accounting
	) throws IOException, SQLException {
		if(isBusinessDisabled(conn, accounting)) throw new SQLException("Business is already disabled: "+accounting);
		if(accounting.equals(getRootBusiness())) throw new SQLException("Not allowed to disable the root business: "+accounting);
		checkAccessDisableLog(conn, source, "disableBusiness", disableLog, false);
		checkAccessBusiness(conn, source, "disableBusiness", accounting);
		List<AccountingCode> packages=getPackagesForBusiness(conn, accounting);
		for (AccountingCode packageName : packages) {
			if(!PackageHandler.isPackageDisabled(conn, packageName)) {
				throw new SQLException("Cannot disable Business '"+accounting+"': Package not disabled: "+packageName);
			}
		}

		conn.executeUpdate(
			"update account.\"Account\" set disable_log=? where accounting=?",
			disableLog,
			accounting
		);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, getServersForBusiness(conn, accounting), false);
	}

	public static void disableBusinessAdministrator(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int disableLog,
		UserId username
	) throws IOException, SQLException {
		if(isBusinessAdministratorDisabled(conn, username)) throw new SQLException("BusinessAdministrator is already disabled: "+username);
		checkAccessDisableLog(conn, source, "disableBusinessAdministrator", disableLog, false);
		UsernameHandler.checkAccessUsername(conn, source, "disableBusinessAdministrator", username);

		conn.executeUpdate(
			"update account.\"Administrator\" set disable_log=? where username=?",
			disableLog,
			username
		);

		// Notify all clients of the update
		AccountingCode accounting=UsernameHandler.getBusinessForUsername(conn, username);
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, getServersForBusiness(conn, accounting), false);
	}

	public static void enableBusiness(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode accounting
	) throws IOException, SQLException {
		checkAccessBusiness(conn, source, "enableBusiness", accounting);

		int disableLog=getDisableLogForBusiness(conn, accounting);
		if(disableLog==-1) throw new SQLException("Business is already enabled: "+accounting);
		checkAccessDisableLog(conn, source, "enableBusiness", disableLog, true);

		if(isBusinessCanceled(conn, accounting)) throw new SQLException("Unable to enable Business, Business canceled: "+accounting);

		conn.executeUpdate(
			"update account.\"Account\" set disable_log=null where accounting=?",
			accounting
		);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accounting, getServersForBusiness(conn, accounting), false);
	}

	public static void enableBusinessAdministrator(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		UserId username
	) throws IOException, SQLException {
		int disableLog=getDisableLogForBusinessAdministrator(conn, username);
		if(disableLog==-1) throw new SQLException("BusinessAdministrator is already enabled: "+username);
		checkAccessDisableLog(conn, source, "enableBusinessAdministrator", disableLog, true);
		UsernameHandler.checkAccessUsername(conn, source, "enableBusinessAdministrator", username);

		conn.executeUpdate(
			"update account.\"Administrator\" set disable_log=null where username=?",
			username
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.BUSINESS_ADMINISTRATORS,
			UsernameHandler.getBusinessForUsername(conn, username),
			UsernameHandler.getServersForUsername(conn, username),
			false
		);
	}

	/**
	 * Generates a random, unused support code.
	 */
	public static String generateSupportCode(
		DatabaseConnection conn
	) throws IOException, SQLException {
		Random random = MasterServer.getRandom();
		StringBuilder SB = new StringBuilder(11);
		for(int range=1000000; range<1000000000; range *= 10) {
			for(int attempt=0; attempt<1000; attempt++) {
				SB.setLength(0);
				SB.append((char)('a'+random.nextInt('z'+1-'a')));
				SB.append((char)('a'+random.nextInt('z'+1-'a')));
				SB.append(random.nextInt(range));
				String supportCode = SB.toString();
				if(conn.executeBooleanQuery("select (select support_code from account.\"Administrator\" where support_code=?) is null", supportCode)) return supportCode;
			}
		}
		throw new SQLException("Failed to generate support code after thousands of attempts");
	}

	public static AccountingCode generateAccountingCode(
		DatabaseConnection conn,
		AccountingCode template
	) throws IOException, SQLException {
		// Load the entire list of accounting codes
		Set<AccountingCode> codes=conn.executeObjectCollectionQuery(
			new HashSet<AccountingCode>(),
			ObjectFactories.accountingCodeFactory,
			"select accounting from account.\"Account\""
		);
		// Find one that is not used
		for(int c=1;c<Integer.MAX_VALUE;c++) {
			AccountingCode accounting;
			try {
				accounting = AccountingCode.valueOf(template.toString()+c);
			} catch(ValidationException e) {
				throw new SQLException(e);
			}
			if(!codes.contains(accounting)) return accounting;
		}
		// If could not find one, report and error
		throw new SQLException("Unable to find available accounting code for template: "+template);
	}

	/**
	 * Gets the depth of the business in the business tree.  root_accounting is at depth 1.
	 * 
	 * @return  the depth between 1 and Business.MAXIMUM_BUSINESS_TREE_DEPTH, inclusive.
	 */
	public static int getDepthInBusinessTree(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		int depth=0;
		while(accounting!=null) {
			AccountingCode parent=conn.executeObjectQuery(
				ObjectFactories.accountingCodeFactory,
				"select parent from account.\"Account\" where accounting=?",
				accounting
			);
			depth++;
			accounting=parent;
		}
		if(depth<1 || depth>Business.MAXIMUM_BUSINESS_TREE_DEPTH) throw new SQLException("Unexpected depth: "+depth);
		return depth;
	}

	public static UserId getDisableLogDisabledBy(DatabaseConnection conn, int id) throws IOException, SQLException {
		return conn.executeObjectQuery(
			ObjectFactories.userIdFactory,
			"select disabled_by from account.\"DisableLog\" where id=?",
			id
		);
	}

	public static int getDisableLogForBusiness(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeIntQuery("select coalesce(disable_log, -1) from account.\"Account\" where accounting=?", accounting);
	}

	final private static Map<UserId,Integer> businessAdministratorDisableLogs=new HashMap<>();
	public static int getDisableLogForBusinessAdministrator(DatabaseConnection conn, UserId username) throws IOException, SQLException {
		synchronized(businessAdministratorDisableLogs) {
			if(businessAdministratorDisableLogs.containsKey(username)) return businessAdministratorDisableLogs.get(username);
			int disableLog=conn.executeIntQuery("select coalesce(disable_log, -1) from account.\"Administrator\" where username=?", username);
			businessAdministratorDisableLogs.put(username, disableLog);
			return disableLog;
		}
	}

	// TODO: Here and all around in AOServ Master, lists are used where objects should be in a unique set
	public static List<AccountingCode> getPackagesForBusiness(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeObjectListQuery(
			ObjectFactories.accountingCodeFactory,
			"select name from billing.\"Package\" where accounting=?",
			accounting
		);
	}

	public static IntList getServersForBusiness(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeIntListQuery("select server from account.\"AccountHost\" where accounting=?", accounting);
	}

	public static AccountingCode getRootBusiness() throws IOException {
		return MasterConfiguration.getRootBusiness();
	}

	public static boolean isAccountingAvailable(
		DatabaseConnection conn,
		AccountingCode accounting
	) throws IOException, SQLException {
		return conn.executeIntQuery("select count(*) from account.\"Account\" where accounting=?", accounting)==0;
	}

	public static boolean isBusinessAdministratorPasswordSet(
		DatabaseConnection conn,
		RequestSource source,
		UserId username
	) throws IOException, SQLException {
		UsernameHandler.checkAccessUsername(conn, source, "isBusinessAdministratorPasswordSet", username);
		return conn.executeBooleanQuery("select password is not null from account.\"Administrator\" where username=?", username);
	}

	public static void removeBusinessAdministrator(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		UserId username
	) throws IOException, SQLException {
		if(username.equals(source.getUsername())) throw new SQLException("Not allowed to remove self: "+username);
		UsernameHandler.checkAccessUsername(conn, source, "removeBusinessAdministrator", username);

		removeBusinessAdministrator(conn, invalidateList, username);
	}

	public static void removeBusinessAdministrator(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		UserId username
	) throws IOException, SQLException {
		if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to remove Username named '"+LinuxAccount.MAIL+'\'');

		AccountingCode accounting=UsernameHandler.getBusinessForUsername(conn, username);

		conn.executeUpdate("delete from master.\"AdministratorPermission\" where username=?", username);
		conn.executeUpdate("delete from account.\"Administrator\" where username=?", username);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
	}

	/**
	 * Removes a <code>BusinessServer</code>.
	 */
	public static void removeBusinessServer(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		AccountingCode accounting = conn.executeObjectQuery(
			ObjectFactories.accountingCodeFactory,
			"select accounting from account.\"AccountHost\" where id=?",
			id
		);
		int server=conn.executeIntQuery("select server from account.\"AccountHost\" where id=?", id);

		// Must be allowed to access this Business
		checkAccessBusiness(conn, source, "removeBusinessServer", accounting);

		// Do not remove the default unless it is the only one left
		if(
			conn.executeBooleanQuery("select is_default from account.\"AccountHost\" where id=?", id)
			&& conn.executeIntQuery("select count(*) from account.\"AccountHost\" where accounting=?", accounting)>1
		) {
			throw new SQLException("Cannot remove the default business_server unless it is the last business_server for a business: "+id);
		}

		removeBusinessServer(
			conn,
			invalidateList,
			id
		);
	}

	/**
	 * Removes a <code>BusinessServer</code>.
	 */
	public static void removeBusinessServer(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		AccountingCode accounting = conn.executeObjectQuery(
			ObjectFactories.accountingCodeFactory,
			"select accounting from account.\"AccountHost\" where id=?",
			id
		);
		int server=conn.executeIntQuery("select server from account.\"AccountHost\" where id=?", id);

		// No children should be able to access the server
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      bs.id\n"
				+ "    from\n"
				+ "      account.\"Account\" bu,\n"
				+ "      account.\"AccountHost\" bs\n"
				+ "    where\n"
				+ "      bu.parent=?\n"
				+ "      and bu.accounting=bs.accounting\n"
				+ "      and bs.server=?\n"
				+ "    limit 1\n"
				+ "  ) is not null",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still has at least one child Business able to access Server="+server);

		/*
		 * Business must not have any resources on the server
		 */
		// email.Pipe
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      ep.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      email.\"Pipe\" ep\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=ep.package\n"
				+ "      and ep.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one EmailPipe on Server="+server);

		// web.Site
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      hs.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      web.\"Site\" hs\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=hs.package\n"
				+ "      and hs.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one HttpdSite on Server="+server);

		// net.IpAddress
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      ia.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      net.\"IpAddress\" ia,\n"
				+ "      net.\"Device\" nd\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.id=ia.package\n"
				+ "      and ia.\"netDevice\"=nd.id\n"
				+ "      and nd.server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one net.IpAddress on Server="+server);

		// linux.UserServer
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      lsa.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      account.\"Username\" un,\n"
				+ "      linux.\"UserServer\" lsa\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=un.package\n"
				+ "      and un.username=lsa.username\n"
				+ "      and lsa.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one LinuxServerAccount on Server="+server);

		// linux.GroupServer
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      lsg.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      linux.\"Group\" lg,\n"
				+ "      linux.\"GroupServer\" lsg\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=lg.package\n"
				+ "      and lg.name=lsg.name\n"
				+ "      and lsg.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one LinuxServerGroup on Server="+server);

		// mysql.Database
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      md.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      mysql.\"Database\" md,\n"
				+ "      mysql.\"Server\" ms\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=md.package\n"
				+ "      and md.mysql_server=ms.net_bind\n"
				+ "      and ms.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one MySQLDatabase on Server="+server);

		// mysql.UserServer
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      msu.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      account.\"Username\" un,\n"
				+ "      mysql.\"UserServer\" msu,\n"
				+ "      mysql.\"Server\" ms\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=un.package\n"
				+ "      and un.username=msu.username\n"
				+ "      and msu.mysql_server=ms.net_bind\n"
				+ "      and ms.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one MySQLServerUser on Server="+server);

		// net.Bind
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      nb.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      net.\"Bind\" nb\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=nb.package\n"
				+ "      and nb.server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one NetBind on Server="+server);

		// postgresql.Database
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      pd.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      account.\"Username\" un,\n"
				+ "      postgresql.\"Server\" ps,\n"
				+ "      postgresql.\"UserServer\" psu,\n"
				+ "      postgresql.\"Database\" pd\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=un.package\n"
				+ "      and ps.ao_server=?\n"
				+ "      and un.username=psu.username and ps.bind = psu.postgres_server\n"
				+ "      and pd.datdba=psu.id\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one PostgresDatabase on Server="+server);

		// postgresql.UserServer
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      psu.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      account.\"Username\" un,\n"
				+ "      postgresql.\"Server\" ps,\n"
				+ "      postgresql.\"UserServer\" psu\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=un.package\n"
				+ "      and ps.ao_server=?\n"
				+ "      and un.username=psu.username and ps.bind = psu.postgres_server\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one PostgresServerUser on Server="+server);

		// email.Domain
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      ed.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      email.\"Domain\" ed\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=ed.package\n"
				+ "      and ed.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one EmailDomain on Server="+server);

		// email.SmtpRelay
		if(
			conn.executeBooleanQuery(
				"select\n"
				+ "  (\n"
				+ "    select\n"
				+ "      esr.id\n"
				+ "    from\n"
				+ "      billing.\"Package\" pk,\n"
				+ "      email.\"SmtpRelay\" esr\n"
				+ "    where\n"
				+ "      pk.accounting=?\n"
				+ "      and pk.name=esr.package\n"
				+ "      and esr.ao_server is not null\n"
				+ "      and esr.ao_server=?\n"
				+ "    limit 1\n"
				+ "  )\n"
				+ "  is not null\n",
				accounting,
				server
			)
		) throw new SQLException("Business="+accounting+" still owns at least one EmailSmtpRelay on Server="+server);

		conn.executeUpdate("delete from account.\"AccountHost\" where id=?", id);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.AO_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.VIRTUAL_SERVERS, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.NET_DEVICES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
		invalidateList.addTable(conn, SchemaTable.TableID.IP_ADDRESSES, InvalidateList.allBusinesses, InvalidateList.allServers, true);
	}

	public static void removeDisableLog(
		DatabaseConnection conn,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		AccountingCode accounting=getBusinessForDisableLog(conn, id);

		conn.executeUpdate("delete from account.\"DisableLog\" where id=?", id);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.DISABLE_LOG, accounting, InvalidateList.allServers, false);
	}

	public static void setBusinessAccounting(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		AccountingCode oldAccounting,
		AccountingCode newAccounting
	) throws IOException, SQLException {
		checkAccessBusiness(conn, source, "setBusinessAccounting", oldAccounting);

		conn.executeUpdate("update account.\"Account\" set accounting=? where accounting=?", newAccounting, oldAccounting);

		// Notify all clients of the update
		Collection<AccountingCode> accts=InvalidateList.getCollection(oldAccounting, newAccounting);
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESSES, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_PROFILES, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_SERVERS, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.CREDIT_CARDS, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.DISABLE_LOG, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.MONTHLY_CHARGES, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.NOTICE_LOG, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.PACKAGE_DEFINITIONS, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.PACKAGES, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.SERVERS, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.TICKETS, accts, InvalidateList.allServers, false);
		invalidateList.addTable(conn, SchemaTable.TableID.TRANSACTIONS, accts, InvalidateList.allServers, false);
	}

	public static void setBusinessAdministratorPassword(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		UserId username,
		String plaintext
	) throws IOException, SQLException {
		// An administrator may always reset their own passwords
		if(!username.equals(source.getUsername())) checkPermission(conn, source, "setBusinessAdministratorPassword", AOServPermission.Permission.set_business_administrator_password);

		UsernameHandler.checkAccessUsername(conn, source, "setBusinessAdministratorPassword", username);
		if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set password for BusinessAdministrator named '"+LinuxAccount.MAIL+'\'');

		if(isBusinessAdministratorDisabled(conn, username)) throw new SQLException("Unable to set password, BusinessAdministrator disabled: "+username);

		if(plaintext!=null && plaintext.length()>0) {
			// Perform the password check here, too.
			List<PasswordChecker.Result> results=BusinessAdministrator.checkPassword(username, plaintext);
			if(PasswordChecker.hasResults(results)) throw new SQLException("Invalid password: "+PasswordChecker.getResultsString(results).replace('\n', '|'));
		}

		String encrypted =
			plaintext==null || plaintext.length()==0
			? null
			: HashedPassword.hash(plaintext)
		;

		AccountingCode accounting=UsernameHandler.getBusinessForUsername(conn, username);
		conn.executeUpdate("update account.\"Administrator\" set password=? where username=?", encrypted, username);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
	}

	/**
	 * Sets a business_administrators profile.
	 */
	public static void setBusinessAdministratorProfile(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		UserId username,
		String name,
		String title,
		Date birthday,
		boolean isPrivate,
		String workPhone,
		String homePhone,
		String cellPhone,
		String fax,
		String email,
		String address1,
		String address2,
		String city,
		String state,
		String country,
		String zip
	) throws IOException, SQLException {
		UsernameHandler.checkAccessUsername(conn, source, "setBusinessSdministratorProfile", username);
		if(username.equals(LinuxAccount.MAIL)) throw new SQLException("Not allowed to set BusinessAdministrator profile for user '"+LinuxAccount.MAIL+'\'');

		ValidationResult emailResult = Email.validate(email);
		if(!emailResult.isValid()) throw new SQLException("Invalid format for email: " + emailResult);

		if (country!=null && country.equals(CountryCode.US)) state=convertUSState(conn, state);

		AccountingCode accounting=UsernameHandler.getBusinessForUsername(conn, username);
		conn.executeUpdate(
			"update account.\"Administrator\" set name=?, title=?, birthday=?, private=?, work_phone=?, home_phone=?, cell_phone=?, fax=?, email=?, address1=?, address2=?, city=?, state=?, country=?, zip=? where username=?",
			name,
			title,
			birthday==null?Null.DATE:birthday,
			isPrivate,
			workPhone,
			homePhone,
			cellPhone,
			fax,
			email,
			address1,
			address2,
			city,
			state,
			country,
			zip,
			username
		);

		// Notify all clients of the update
		invalidateList.addTable(conn, SchemaTable.TableID.BUSINESS_ADMINISTRATORS, accounting, InvalidateList.allServers, false);
	}

	/**
	 * Sets the default Server for a Business
	 */
	public static void setDefaultBusinessServer(
		DatabaseConnection conn,
		RequestSource source,
		InvalidateList invalidateList,
		int id
	) throws IOException, SQLException {
		AccountingCode accounting = conn.executeObjectQuery(
			ObjectFactories.accountingCodeFactory,
			"select accounting from account.\"AccountHost\" where id=?",
			id
		);

		checkAccessBusiness(conn, source, "setDefaultBusinessServer", accounting);

		if(isBusinessDisabled(conn, accounting)) throw new SQLException("Unable to set the default BusinessServer, Business disabled: "+accounting);

		// Update the table
		conn.executeUpdate(
			"update account.\"AccountHost\" set is_default=true where id=?",
			id
		);
		conn.executeUpdate(
			"update account.\"AccountHost\" set is_default=false where accounting=? and id!=?",
			accounting,
			id
		);

		// Notify all clients of the update
		invalidateList.addTable(
			conn,
			SchemaTable.TableID.BUSINESS_SERVERS,
			accounting,
			InvalidateList.allServers,
			false
		);
	}

	public static BusinessAdministrator getBusinessAdministrator(DatabaseConnection conn, UserId username) throws IOException, SQLException {
		synchronized(businessAdministratorsLock) {
			if(businessAdministrators == null) {
				businessAdministrators = conn.executeQuery(
					(ResultSet results) -> {
						Map<UserId,BusinessAdministrator> table=new HashMap<>();
						while(results.next()) {
							BusinessAdministrator ba=new BusinessAdministrator();
							ba.init(results);
							table.put(ba.getKey(), ba);
						}
						return table;
					},
					"select * from account.\"Administrator\""
				);
			}
			return businessAdministrators.get(username);
		}
	}

	public static void invalidateTable(SchemaTable.TableID tableID) {
		if(tableID==SchemaTable.TableID.BUSINESS_ADMINISTRATORS) {
			synchronized(businessAdministratorsLock) {
				businessAdministrators=null;
			}
			synchronized(disabledBusinessAdministrators) {
				disabledBusinessAdministrators.clear();
			}
			synchronized(businessAdministratorDisableLogs) {
				businessAdministratorDisableLogs.clear();
			}
		} else if(tableID==SchemaTable.TableID.BUSINESSES) {
			synchronized(usernameBusinessesLock) {
				usernameBusinesses=null;
			}
			synchronized(disabledBusinesses) {
				disabledBusinesses.clear();
			}
		} else if(tableID==SchemaTable.TableID.BUSINESS_ADMINISTRATOR_PERMISSIONS) {
			synchronized(cachedPermissionsLock) {
				cachedPermissions = null;
			}
		}
	}

	public static AccountingCode getParentBusiness(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeObjectQuery(
			ObjectFactories.accountingCodeFactory,
			"select parent from account.\"Account\" where accounting=?",
			accounting
		);
	}

	public static String getTechnicalEmail(DatabaseConnection conn, String accountingCode) throws IOException, SQLException {
		return conn.executeStringQuery(
			"select technical_email from account.\"Profile\" where accounting=? order by priority desc limit 1",
			accountingCode
		);
	}

	public static boolean isBusinessAdministrator(DatabaseConnection conn, UserId username) throws IOException, SQLException {
		return getBusinessAdministrator(conn, username)!=null;
	}

	public static boolean isBusinessAdministratorDisabled(DatabaseConnection conn, UserId username) throws IOException, SQLException {
		Boolean O;
		synchronized(disabledBusinessAdministrators) {
			O=disabledBusinessAdministrators.get(username);
		}
		if(O!=null) return O;
		boolean isDisabled=getDisableLogForBusinessAdministrator(conn, username)!=-1;
		synchronized(disabledBusinessAdministrators) {
			disabledBusinessAdministrators.put(username, isDisabled);
		}
		return isDisabled;
	}

	public static boolean isBusinessDisabled(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		synchronized(disabledBusinesses) {
			Boolean O=disabledBusinesses.get(accounting);
			if(O!=null) return O;
			boolean isDisabled=getDisableLogForBusiness(conn, accounting)!=-1;
			disabledBusinesses.put(accounting, isDisabled);
			return isDisabled;
		}
	}

	public static boolean isBusinessCanceled(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeBooleanQuery("select canceled is not null from account.\"Account\" where accounting=?", accounting);
	}

	public static boolean isBusinessBillParent(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeBooleanQuery("select bill_parent from account.\"Account\" where accounting=?", accounting);
	}

	public static boolean canSeePrices(DatabaseConnection conn, RequestSource source) throws IOException, SQLException {
		return canSeePrices(conn, UsernameHandler.getBusinessForUsername(conn, source.getUsername()));
	}

	public static boolean canSeePrices(DatabaseConnection conn, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeBooleanQuery("select can_see_prices from account.\"Account\" where accounting=?", accounting);
	}

	public static boolean isBusinessOrParent(DatabaseConnection conn, AccountingCode parentAccounting, AccountingCode accounting) throws IOException, SQLException {
		return conn.executeBooleanQuery("select account.is_account_or_parent(?,?)", parentAccounting, accounting);
	}

	public static boolean canSwitchUser(DatabaseConnection conn, UserId authenticatedAs, UserId connectAs) throws IOException, SQLException {
		AccountingCode authAccounting=UsernameHandler.getBusinessForUsername(conn, authenticatedAs);
		AccountingCode connectAccounting=UsernameHandler.getBusinessForUsername(conn, connectAs);
		// Cannot switch within same business
		if(authAccounting.equals(connectAccounting)) return false;
		return conn.executeBooleanQuery(
			"select\n"
			+ "  (select can_switch_users from account.\"Administrator\" where username=?)\n"
			+ "  and account.is_account_or_parent(?,?)",
			authenticatedAs,
			authAccounting,
			connectAccounting
		);
	}

	/**
	 * Gets the list of both technical and billing contacts for all not-canceled account.Account.
	 *
	 * @return  a <code>HashMap</code> of <code>ArrayList</code>
	 */
	public static Map<AccountingCode,List<String>> getBusinessContacts(DatabaseConnection conn) throws IOException, SQLException {
		return conn.executeQuery(
			(ResultSet results) -> {
				// Load the list of account.Account and their contacts
				Map<AccountingCode,List<String>> businessContacts = new HashMap<>();
				List<String> foundAddresses = new SortedArrayList<>();
				try {
					while(results.next()) {
						AccountingCode accounting=AccountingCode.valueOf(results.getString(1));
						if(!businessContacts.containsKey(accounting)) {
							List<String> uniqueAddresses=new ArrayList<>();
							foundAddresses.clear();
							// billing contacts
							List<String> addresses=StringUtility.splitStringCommaSpace(results.getString(2));
							for (String address : addresses) {
								String addy = address.toLowerCase();
								if(!foundAddresses.contains(addy)) {
									uniqueAddresses.add(addy);
									foundAddresses.add(addy);
								}
							}
							// technical contacts
							addresses=StringUtility.splitStringCommaSpace(results.getString(3));
							for (String address : addresses) {
								String addy = address.toLowerCase();
								if(!foundAddresses.contains(addy)) {
									uniqueAddresses.add(addy);
									foundAddresses.add(addy);
								}
							}
							businessContacts.put(accounting, uniqueAddresses);
						}
					}
				} catch(ValidationException e) {
					throw new SQLException(e.getLocalizedMessage(), e);
				}
				return businessContacts;
			},
			"select bp.accounting, bp.billing_email, bp.technical_email from account.\"Profile\" bp, account.\"Account\" bu where bp.accounting=bu.accounting and bu.canceled is null order by bp.accounting, bp.priority desc"
		);
	}

	/**
	 * Gets the best estimate of a business for a list of email addresses or <code>null</code> if can't determine.
	 * The algorithm takes these steps.
	 * <ol>
	 *   <li>Look for exact matches in billing and technical contacts, with a weight of 10.</li>
	 *   <li>Look for matches in <code>email.Domain</code>, with a weight of 5</li>
	 *   <li>Look for matches in <code>web.VirtualHostName</code> with a weight of 1</li>
	 *   <li>Look for matches in <code>dns.Zone</code> with a weight of 1</li>
	 *   <li>Add up the weights per business</li>
	 *   <li>Find the highest weight</li>
	 *   <li>Follow the bill_parents up to top billing level</li>
	 * </ol>
	 */
	public static AccountingCode getBusinessFromEmailAddresses(DatabaseConnection conn, List<String> addresses) throws IOException, SQLException {
		// Load the list of account.Account and their contacts
		Map<AccountingCode,List<String>> businessContacts=getBusinessContacts(conn);

		// The cumulative weights are added up here, per business
		Map<AccountingCode,Integer> businessWeights=new HashMap<>();

		// Go through all addresses
		for (String address : addresses) {
			String addy = address.toLowerCase();
			// Look for billing and technical contact matches, 10 points each
			Iterator<AccountingCode> I=businessContacts.keySet().iterator();
			while(I.hasNext()) {
				AccountingCode accounting=I.next();
				List<String> list=businessContacts.get(accounting);
				for (String contact : list) {
					if(addy.equals(contact)) addWeight(businessWeights, accounting, 10);
				}
			}

			// Parse the domain
			int pos=addy.lastIndexOf('@');
			if(pos!=-1) {
				String domain=addy.substring(pos+1);
				if(domain.length()>0) {
					// Look for matches in email.Domain, 5 points each
					List<AccountingCode> domains=conn.executeObjectCollectionQuery(
						new ArrayList<AccountingCode>(),
						ObjectFactories.accountingCodeFactory,
						"select\n"
						+ "  pk.accounting\n"
						+ "from\n"
						+ "  email.\"Domain\" ed,\n"
						+ "  billing.\"Package\" pk\n"
						+ "where\n"
						+ "  ed.domain=?\n"
						+ "  and ed.package=pk.name",
						domain
					);
					for (AccountingCode accounting : domains) {
						addWeight(businessWeights, accounting, 5);
					}
					// Look for matches in web.VirtualHostName, 1 point each
					List<AccountingCode> sites=conn.executeObjectCollectionQuery(
						new ArrayList<AccountingCode>(),
						ObjectFactories.accountingCodeFactory,
						"select\n"
						+ "  pk.accounting\n"
						+ "from\n"
						+ "  web.\"VirtualHostName\" hsu,\n"
						+ "  web.\"VirtualHost\" hsb,\n"
						+ "  web.\"Site\" hs,\n"
						+ "  billing.\"Package\" pk\n"
						+ "where\n"
						+ "  hsu.hostname=?\n"
						+ "  and hsu.httpd_site_bind=hsb.id\n"
						+ "  and hsb.httpd_site=hs.id\n"
						+ "  and hs.package=pk.name",
						domain
					);
					for (AccountingCode accounting : sites) {
						addWeight(businessWeights, accounting, 1);
					}
					// Look for matches in dns.Zone, 1 point each
					List<AccountingCode> zones=conn.executeObjectCollectionQuery(
						new ArrayList<AccountingCode>(),
						ObjectFactories.accountingCodeFactory,
						"select\n"
						+ "  pk.accounting\n"
						+ "from\n"
						+ "  dns.\"Zone\" dz,\n"
						+ "  billing.\"Package\" pk\n"
						+ "where\n"
						+ "  dz.zone=?\n"
						+ "  and dz.package=pk.name",
						domain
					);
					for (AccountingCode accounting : zones) {
						addWeight(businessWeights, accounting, 1);
					}
				}
			}
		}

		// Find the highest weight
		Iterator<AccountingCode> I=businessWeights.keySet().iterator();
		int highest=0;
		AccountingCode highestAccounting=null;
		while(I.hasNext()) {
			AccountingCode accounting=I.next();
			int weight=businessWeights.get(accounting);
			if(weight>highest) {
				highest=weight;
				highestAccounting=accounting;
			}
		}

		// Follow the bill_parent flags toward the top, but skipping canceled
		while(
			highestAccounting!=null
			&& (
				isBusinessCanceled(conn, highestAccounting)
				|| isBusinessBillParent(conn, highestAccounting)
			)
		) {
			highestAccounting=getParentBusiness(conn, highestAccounting);
		}

		// Do not accept root business
		if(highestAccounting!=null && highestAccounting.equals(getRootBusiness())) highestAccounting=null;

		// Return result
		return highestAccounting;
	}

	private static void addWeight(Map<AccountingCode,Integer> businessWeights, AccountingCode accounting, int weight) {
		Integer I=businessWeights.get(accounting);
		int previous=I==null ? 0 : I;
		businessWeights.put(accounting, previous + weight);
	}

	public static boolean canBusinessAccessServer(DatabaseConnection conn, AccountingCode accounting, int server) throws IOException, SQLException {
		return conn.executeBooleanQuery(
			"select\n"
			+ "  (\n"
			+ "    select\n"
			+ "      id\n"
			+ "    from\n"
			+ "      account.\"AccountHost\"\n"
			+ "    where\n"
			+ "      accounting=?\n"
			+ "      and server=?\n"
			+ "    limit 1\n"
			+ "  )\n"
			+ "  is not null\n",
			accounting,
			server
		);
	}

	public static void checkBusinessAccessServer(DatabaseConnection conn, RequestSource source, String action, AccountingCode accounting, int server) throws IOException, SQLException {
		if(!canBusinessAccessServer(conn, accounting, server)) {
			String message=
			"accounting="
			+accounting
			+" is not allowed to access server.id="
			+server
			+": action='"
			+action
			+"'"
			;
			throw new SQLException(message);
		}
	}
}
