package com.aoindustries.aoserv.master;

/*
 * Copyright 2001-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOServPermission;
import com.aoindustries.aoserv.client.PaymentType;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.client.TransactionType;
import com.aoindustries.creditcards.AuthorizationResult;
import com.aoindustries.creditcards.CreditCard;
import com.aoindustries.creditcards.CreditCardProcessor;
import com.aoindustries.creditcards.MerchantServicesProviderFactory;
import com.aoindustries.creditcards.Transaction;
import com.aoindustries.creditcards.TransactionRequest;
import com.aoindustries.cron.CronDaemon;
import com.aoindustries.cron.CronJob;
import com.aoindustries.email.ProcessTimer;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.sql.WrappedSQLException;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;

/**
 * The <code>CreditCardHandler</code> handles all the accesses to the <code>credit_cards</code> table.
 *
 * TODO: Deactivate immediately on expired card
 * TODO: Retry failed cards on the 7th and 14th, then deactivate.
 *
 * @author  AO Industries, Inc.
 */
final public class CreditCardHandler implements CronJob {

    /**
     * The maximum time for a processing pass.
     */
    private static final long TIMER_MAX_TIME=60L*60*1000;

    /**
     * The interval in which the administrators will be reminded.
     */
    private static final long TIMER_REMINDER_INTERVAL=2L*60*60*1000;

    private static boolean started=false;

    public static void start() {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "start()", null);
        try {
            synchronized(System.out) {
                if(!started) {
                    System.out.print("Starting CreditCardHandler: ");
                    CronDaemon.addCronJob(new CreditCardHandler(), MasterServer.getErrorHandler());
                    started=true;
                    System.out.println("Done");
                }
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void checkAccessCreditCard(MasterDatabaseConnection conn, RequestSource source, String action, int pkey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, CreditCardHandler.class, "checkAccessCreditCard(MasterDatabaseConnection,RequestSource,String,int)", null);
        try {
            BusinessHandler.checkPermission(conn, source, action, AOServPermission.Permission.get_credit_cards);
            BusinessHandler.checkAccessBusiness(
                conn,
                source,
                action,
                getBusinessForCreditCard(conn, pkey)
            );
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void checkAccessCreditCardProcessor(MasterDatabaseConnection conn, RequestSource source, String action, String processor) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, CreditCardHandler.class, "checkAccessCreditCardProcessor(MasterDatabaseConnection,RequestSource,String,String)", null);
        try {
            BusinessHandler.checkAccessBusiness(
                conn,
                source,
                action,
                getBusinessForCreditCardProcessor(conn, processor)
            );
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void checkAccessCreditCardTransaction(MasterDatabaseConnection conn, RequestSource source, String action, int pkey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, CreditCardHandler.class, "checkAccessCreditCardTransaction(MasterDatabaseConnection,RequestSource,String,int)", null);
        try {
            checkAccessCreditCardProcessor(
                conn,
                source,
                action,
                getCreditCardProcessorForCreditCardTransaction(conn, pkey)
            );
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void checkAccessEncryptionKey(MasterDatabaseConnection conn, RequestSource source, String action, int pkey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, CreditCardHandler.class, "checkAccessEncryptionKey(MasterDatabaseConnection,RequestSource,String,int)", null);
        try {
            BusinessHandler.checkAccessBusiness(
                conn,
                source,
                action,
                getBusinessForEncryptionKey(conn, pkey)
            );
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Creates a new <code>CreditCard</code>.
     */
    public static int addCreditCard(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String processorName,
        String accounting,
        String groupName,
        String cardInfo,
        String providerUniqueId,
        String firstName,
        String lastName,
        String companyName,
        String email,
        String phone,
        String fax,
        String customerTaxId,
        String streetAddress1,
        String streetAddress2,
        String city,
        String state,
        String postalCode,
        String countryCode,
        String principalName,
        String description,
        String encryptedCardNumber,
        String encryptedExpiration,
        int encryptionFrom,
        int encryptionRecipient
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "addCreditCard", AOServPermission.Permission.add_credit_card);
        BusinessHandler.checkAccessBusiness(conn, source, "addCreditCard", accounting);
        if(encryptionFrom!=-1) checkAccessEncryptionKey(conn, source, "addCreditCard", encryptionFrom);
        if(encryptionRecipient!=-1) checkAccessEncryptionKey(conn, source, "addCreditCard", encryptionRecipient);

        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('credit_cards_pkey_seq')");

        if(encryptedCardNumber==null && encryptedExpiration==null && encryptionFrom==-1 && encryptionRecipient==-1) {
            conn.executeUpdate(
                "insert into credit_cards values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?,false,true,null,null,?,null,null,null,null,null,null)",
                pkey,
                processorName,
                accounting,
                groupName,
                cardInfo,
                providerUniqueId,
                firstName,
                lastName,
                companyName,
                email,
                phone,
                fax,
                customerTaxId,
                streetAddress1,
                streetAddress2,
                city,
                state,
                postalCode,
                countryCode,
                source.getUsername(),
                principalName,
                description
            );
        } else if(encryptedCardNumber!=null && encryptedExpiration!=null && encryptionFrom!=-1 && encryptionRecipient!=-1) {
            conn.executeUpdate(
                "insert into credit_cards values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),?,?,false,true,null,null,?,?,?,?,?,?,?)",
                pkey,
                processorName,
                accounting,
                groupName,
                cardInfo,
                providerUniqueId,
                firstName,
                lastName,
                companyName,
                email,
                phone,
                fax,
                customerTaxId,
                streetAddress1,
                streetAddress2,
                city,
                state,
                postalCode,
                countryCode,
                source.getUsername(),
                principalName,
                description,
                encryptedCardNumber,
                encryptionFrom,
                encryptionRecipient,
                encryptedExpiration,
                encryptionFrom,
                encryptionRecipient
            );
        } else throw new SQLException("encryptedCardNumber, encryptedExpiration, encryptionFrom, and encryptionRecipient must either all be null or none null");

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.CREDIT_CARDS, accounting, InvalidateList.allServers, false);
        return pkey;
    }

    public static void creditCardDeclined(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String reason
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "creditCardDeclined(MasterDatabaseConnection,RequestSource,InvalidateList,int,String)", null);
        try {
            BankAccountHandler.checkAccounting(conn, source, "creditCardDeclined");
            checkAccessCreditCard(conn, source, "creditCardDeclined", pkey);

            conn.executeUpdate(
                "update credit_cards set active=false, deactivated_on=now(), deactivate_reason=? where credit_cards.pkey=?",
                reason,
                pkey
            );

            // Notify all clients of the update
            invalidateList.addTable(
                conn,
                SchemaTable.TableID.CREDIT_CARDS,
                CreditCardHandler.getBusinessForCreditCard(conn, pkey),
                InvalidateList.allServers,
                false
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getBusinessForCreditCard(MasterDatabaseConnection conn, int pkey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "getBusinessForCreditCard(MasterDatabaseConnection,int)", null);
        try {
            return conn.executeStringQuery("select accounting from credit_cards where pkey=?", pkey);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getBusinessForCreditCardProcessor(MasterDatabaseConnection conn, String processor) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "getBusinessForCreditCardProcessor(MasterDatabaseConnection,String)", null);
        try {
            return conn.executeStringQuery("select accounting from credit_card_processors where provider_id=?", processor);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getCreditCardProcessorForCreditCardTransaction(MasterDatabaseConnection conn, int pkey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "getCreditCardProcessorForCreditCardTransaction(MasterDatabaseConnection,int)", null);
        try {
            return conn.executeStringQuery("select processor_id from credit_card_transactions where pkey=?", pkey);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String getBusinessForEncryptionKey(MasterDatabaseConnection conn, int pkey) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "getBusinessForEncryptionKey(MasterDatabaseConnection,int)", null);
        try {
            return conn.executeStringQuery("select accounting from encryption_keys where pkey=?", pkey);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void removeCreditCard(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, CreditCardHandler.class, "removeCreditCard(MasterDatabaseConnection,RequestSource,InvalidateList,int)", null);
        try {
            BusinessHandler.checkPermission(conn, source, "removeCreditCard", AOServPermission.Permission.delete_credit_card);
            checkAccessCreditCard(conn, source, "removeCreditCard", pkey);

            removeCreditCard(conn, invalidateList, pkey);
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void removeCreditCard(
        MasterDatabaseConnection conn,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, CreditCardHandler.class, "removeCreditCard(MasterDatabaseConnection,InvalidateList,int)", null);
        try {
            // Grab values for later use
            String business=getBusinessForCreditCard(conn, pkey);

            // Update the database
            conn.executeUpdate("delete from credit_cards where pkey=?", pkey);

            invalidateList.addTable(
                conn,
                SchemaTable.TableID.CREDIT_CARDS,
                business,
                BusinessHandler.getServersForBusiness(conn, business),
                false
            );
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public static void updateCreditCard(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String firstName,
        String lastName,
        String companyName,
        String email,
        String phone,
        String fax,
        String customerTaxId,
        String streetAddress1,
        String streetAddress2,
        String city,
        String state,
        String postalCode,
        String countryCode,
        String description
    ) throws IOException, SQLException {
        // Permission checks
        BusinessHandler.checkPermission(conn, source, "updateCreditCard", AOServPermission.Permission.edit_credit_card);
        checkAccessCreditCard(conn, source, "updateCreditCard", pkey);
        
        // Update row
        conn.executeUpdate(
            "update\n"
            + "  credit_cards\n"
            + "set\n"
            + "  first_name=?,\n"
            + "  last_name=?,\n"
            + "  company_name=?,\n"
            + "  email=?,\n"
            + "  phone=?,\n"
            + "  fax=?,\n"
            + "  customer_tax_id=?,\n"
            + "  street_address1=?,\n"
            + "  street_address2=?,\n"
            + "  city=?,\n"
            + "  state=?,\n"
            + "  postal_code=?,\n"
            + "  country_code=?,\n"
            + "  description=?\n"
            + "where\n"
            + "  pkey=?",
            firstName,
            lastName,
            companyName,
            email,
            phone,
            fax,
            customerTaxId,
            streetAddress1,
            streetAddress2,
            city,
            state,
            postalCode,
            countryCode,
            description,
            pkey
        );
        
        String accounting = getBusinessForCreditCard(conn, pkey);
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.CREDIT_CARDS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static void updateCreditCardNumberAndExpiration(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String maskedCardNumber,
        String encryptedCardNumber,
        String encryptedExpiration,
        int encryptionFrom,
        int encryptionRecipient
    ) throws IOException, SQLException {
        // Permission checks
        BusinessHandler.checkPermission(conn, source, "updateCreditCardNumberAndExpiration", AOServPermission.Permission.edit_credit_card);
        checkAccessCreditCard(conn, source, "updateCreditCardNumberAndExpiration", pkey);
        
        if(encryptionFrom!=-1) checkAccessEncryptionKey(conn, source, "updateCreditCardNumberAndExpiration", encryptionFrom);
        if(encryptionRecipient!=-1) checkAccessEncryptionKey(conn, source, "updateCreditCardNumberAndExpiration", encryptionRecipient);

        if(encryptedCardNumber==null && encryptedExpiration==null && encryptionFrom==-1 && encryptionRecipient==-1) {
            // Update row
            conn.executeUpdate(
                "update\n"
                + "  credit_cards\n"
                + "set\n"
                + "  card_info=?,\n"
                + "  encrypted_card_number=null,\n"
                + "  encryption_card_number_from=null,\n"
                + "  encryption_card_number_recipient=null,\n"
                + "  encrypted_expiration=null,\n"
                + "  encryption_expiration_from=null,\n"
                + "  encryption_expiration_recipient=null\n"
                + "where\n"
                + "  pkey=?",
                maskedCardNumber,
                pkey
            );
        } else if(encryptedCardNumber!=null && encryptedExpiration!=null && encryptionFrom!=-1 && encryptionRecipient!=-1) {
            // Update row
            conn.executeUpdate(
                "update\n"
                + "  credit_cards\n"
                + "set\n"
                + "  card_info=?,\n"
                + "  encrypted_card_number=?,\n"
                + "  encryption_card_number_from=?,\n"
                + "  encryption_card_number_recipient=?,\n"
                + "  encrypted_expiration=?,\n"
                + "  encryption_expiration_from=?,\n"
                + "  encryption_expiration_recipient=?\n"
                + "where\n"
                + "  pkey=?",
                maskedCardNumber,
                encryptedCardNumber,
                encryptionFrom,
                encryptionRecipient,
                encryptedExpiration,
                encryptionFrom,
                encryptionRecipient,
                pkey
            );
        } else throw new SQLException("encryptedCardNumber, encryptedExpiration, encryptionFrom, and encryptionRecipient must either all be null or none null");

        String accounting = getBusinessForCreditCard(conn, pkey);
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.CREDIT_CARDS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static void updateCreditCardExpiration(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String encryptedExpiration,
        int encryptionFrom,
        int encryptionRecipient
    ) throws IOException, SQLException {
        // Permission checks
        BusinessHandler.checkPermission(conn, source, "updateCreditCardExpiration", AOServPermission.Permission.edit_credit_card);
        checkAccessCreditCard(conn, source, "updateCreditCardExpiration", pkey);
        
        checkAccessEncryptionKey(conn, source, "updateCreditCardExpiration", encryptionFrom);
        checkAccessEncryptionKey(conn, source, "updateCreditCardExpiration", encryptionRecipient);

        // Update row
        conn.executeUpdate(
            "update\n"
            + "  credit_cards\n"
            + "set\n"
            + "  encrypted_expiration=?,\n"
            + "  encryption_expiration_from=?,\n"
            + "  encryption_expiration_recipient=?\n"
            + "where\n"
            + "  pkey=?",
            encryptedExpiration,
            encryptionFrom,
            encryptionRecipient,
            pkey
        );

        String accounting = getBusinessForCreditCard(conn, pkey);
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.CREDIT_CARDS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static void reactivateCreditCard(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey
    ) throws IOException, SQLException {
        // Permission checks
        BusinessHandler.checkPermission(conn, source, "reactivateCreditCard", AOServPermission.Permission.edit_credit_card);
        checkAccessCreditCard(conn, source, "reactivateCreditCard", pkey);
        
        // Update row
        conn.executeUpdate(
            "update\n"
            + "  credit_cards\n"
            + "set\n"
            + "  active=true,\n"
            + "  deactivated_on=null,\n"
            + "  deactivate_reason=null\n"
            + "where\n"
            + "  pkey=?",
            pkey
        );

        String accounting = getBusinessForCreditCard(conn, pkey);
        invalidateList.addTable(
            conn,
            SchemaTable.TableID.CREDIT_CARDS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    public static void setCreditCardUseMonthly(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String accounting,
        int pkey
    ) throws IOException, SQLException {
        // Permission checks
        BusinessHandler.checkPermission(conn, source, "setCreditCardUseMonthly", AOServPermission.Permission.edit_credit_card);

        if(pkey==-1) {
            // Clear only
            conn.executeUpdate("update credit_cards set use_monthly=false where accounting=? and use_monthly", accounting);
        } else {
            checkAccessCreditCard(conn, source, "setCreditCardUseMonthly", pkey);

            // Make sure accounting codes match
            if(!accounting.equals(getBusinessForCreditCard(conn, pkey))) throw new SQLException("credit card and business accounting codes do not match");

            // Perform clear and set in one SQL statement - I thinks myself clever right now.
            conn.executeUpdate("update credit_cards set use_monthly=(pkey=?) where accounting=? and use_monthly!=(pkey=?)", pkey, accounting, pkey);
        }

        invalidateList.addTable(
            conn,
            SchemaTable.TableID.CREDIT_CARDS,
            accounting,
            BusinessHandler.getServersForBusiness(conn, accounting),
            false
        );
    }

    /**
     * Creates a new <code>CreditCardTransaction</code>.
     */
    public static int addCreditCardTransaction(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        String processor,
        String accounting,
        String groupName,
        boolean testMode,
        int duplicateWindow,
        String orderNumber,
        String currencyCode,
        String amount,
        String taxAmount,
        boolean taxExempt,
        String shippingAmount,
        String dutyAmount,
        String shippingFirstName,
        String shippingLastName,
        String shippingCompanyName,
        String shippingStreetAddress1,
        String shippingStreetAddress2,
        String shippingCity,
        String shippingState,
        String shippingPostalCode,
        String shippingCountryCode,
        boolean emailCustomer,
        String merchantEmail,
        String invoiceNumber,
        String purchaseOrderNumber,
        String description,
        String creditCardCreatedBy,
        String creditCardPrincipalName,
        String creditCardAccounting,
        String creditCardGroupName,
        String creditCardProviderUniqueId,
        String creditCardMaskedCardNumber,
        String creditCardFirstName,
        String creditCardLastName,
        String creditCardCompanyName,
        String creditCardEmail,
        String creditCardPhone,
        String creditCardFax,
        String creditCardCustomerTaxId,
        String creditCardStreetAddress1,
        String creditCardStreetAddress2,
        String creditCardCity,
        String creditCardState,
        String creditCardPostalCode,
        String creditCardCountryCode,
        String creditCardComments,
        long authorizationTime,
        String authorizationPrincipalName
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "addCreditCardTransaction", AOServPermission.Permission.add_credit_card_transaction);
        checkAccessCreditCardProcessor(conn, source, "addCreditCardTransaction", processor);
        BusinessHandler.checkAccessBusiness(conn, source, "addCreditCardTransaction", accounting);
        BusinessHandler.checkAccessBusiness(conn, source, "addCreditCardTransaction", creditCardAccounting);
        UsernameHandler.checkAccessUsername(conn, source, "addCreditCardTransaction", creditCardCreatedBy);

        return addCreditCardTransaction(
            conn,
            invalidateList,
            processor,
            accounting,
            groupName,
            testMode,
            duplicateWindow,
            orderNumber,
            currencyCode,
            amount,
            taxAmount,
            taxExempt,
            shippingAmount,
            dutyAmount,
            shippingFirstName,
            shippingLastName,
            shippingCompanyName,
            shippingStreetAddress1,
            shippingStreetAddress2,
            shippingCity,
            shippingState,
            shippingPostalCode,
            shippingCountryCode,
            emailCustomer,
            merchantEmail,
            invoiceNumber,
            purchaseOrderNumber,
            description,
            creditCardCreatedBy,
            creditCardPrincipalName,
            creditCardAccounting,
            creditCardGroupName,
            creditCardProviderUniqueId,
            creditCardMaskedCardNumber,
            creditCardFirstName,
            creditCardLastName,
            creditCardCompanyName,
            creditCardEmail,
            creditCardPhone,
            creditCardFax,
            creditCardCustomerTaxId,
            creditCardStreetAddress1,
            creditCardStreetAddress2,
            creditCardCity,
            creditCardState,
            creditCardPostalCode,
            creditCardCountryCode,
            creditCardComments,
            authorizationTime,
            source.getUsername(),
            authorizationPrincipalName
        );
    }

    /**
     * Creates a new <code>CreditCardTransaction</code>.
     */
    public static int addCreditCardTransaction(
        MasterDatabaseConnection conn,
        InvalidateList invalidateList,
        String processor,
        String accounting,
        String groupName,
        boolean testMode,
        int duplicateWindow,
        String orderNumber,
        String currencyCode,
        String amount,
        String taxAmount,
        boolean taxExempt,
        String shippingAmount,
        String dutyAmount,
        String shippingFirstName,
        String shippingLastName,
        String shippingCompanyName,
        String shippingStreetAddress1,
        String shippingStreetAddress2,
        String shippingCity,
        String shippingState,
        String shippingPostalCode,
        String shippingCountryCode,
        boolean emailCustomer,
        String merchantEmail,
        String invoiceNumber,
        String purchaseOrderNumber,
        String description,
        String creditCardCreatedBy,
        String creditCardPrincipalName,
        String creditCardAccounting,
        String creditCardGroupName,
        String creditCardProviderUniqueId,
        String creditCardMaskedCardNumber,
        String creditCardFirstName,
        String creditCardLastName,
        String creditCardCompanyName,
        String creditCardEmail,
        String creditCardPhone,
        String creditCardFax,
        String creditCardCustomerTaxId,
        String creditCardStreetAddress1,
        String creditCardStreetAddress2,
        String creditCardCity,
        String creditCardState,
        String creditCardPostalCode,
        String creditCardCountryCode,
        String creditCardComments,
        long authorizationTime,
        String authorizationUsername,
        String authorizationPrincipalName
    ) throws IOException, SQLException {
        int pkey=conn.executeIntQuery(Connection.TRANSACTION_READ_COMMITTED, false, true, "select nextval('credit_card_transactions_pkey_seq')");

        conn.executeUpdate(
            "insert into credit_card_transactions (\n"
            + "  pkey,\n"
            + "  processor_id,\n"
            + "  accounting,\n"
            + "  group_name,\n"
            + "  test_mode,\n"
            + "  duplicate_window,\n"
            + "  order_number,\n"
            + "  currency_code,\n"
            + "  amount,\n"
            + "  tax_amount,\n"
            + "  tax_exempt,\n"
            + "  shipping_amount,\n"
            + "  duty_amount,\n"
            + "  shipping_first_name,\n"
            + "  shipping_last_name,\n"
            + "  shipping_company_name,\n"
            + "  shipping_street_address1,\n"
            + "  shipping_street_address2,\n"
            + "  shipping_city,\n"
            + "  shipping_state,\n"
            + "  shipping_postal_code,\n"
            + "  shipping_country_code,\n"
            + "  email_customer,\n"
            + "  merchant_email,\n"
            + "  invoice_number,\n"
            + "  purchase_order_number,\n"
            + "  description,\n"
            + "  credit_card_created_by,\n"
            + "  credit_card_principal_name,\n"
            + "  credit_card_accounting,\n"
            + "  credit_card_group_name,\n"
            + "  credit_card_provider_unique_id,\n"
            + "  credit_card_masked_card_number,\n"
            + "  credit_card_first_name,\n"
            + "  credit_card_last_name,\n"
            + "  credit_card_company_name,\n"
            + "  credit_card_email,\n"
            + "  credit_card_phone,\n"
            + "  credit_card_fax,\n"
            + "  credit_card_customer_tax_id,\n"
            + "  credit_card_street_address1,\n"
            + "  credit_card_street_address2,\n"
            + "  credit_card_city,\n"
            + "  credit_card_state,\n"
            + "  credit_card_postal_code,\n"
            + "  credit_card_country_code,\n"
            + "  credit_card_comments,\n"
            + "  authorization_time,\n"
            + "  authorization_username,\n"
            + "  authorization_principal_name,\n"
            + "  status\n"
            + ") values (?,?,?,?,?,?,?,?,?::decimal(9,2),?::decimal(9,2),?,?::decimal(9,2),?::decimal(9,2),?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PROCESSING')",
            pkey,
            processor,
            accounting,
            groupName,
            testMode,
            duplicateWindow,
            orderNumber,
            currencyCode,
            amount,
            taxAmount,
            taxExempt,
            shippingAmount,
            dutyAmount,
            shippingFirstName,
            shippingLastName,
            shippingCompanyName,
            shippingStreetAddress1,
            shippingStreetAddress2,
            shippingCity,
            shippingState,
            shippingPostalCode,
            shippingCountryCode,
            emailCustomer,
            merchantEmail,
            invoiceNumber,
            purchaseOrderNumber,
            description,
            creditCardCreatedBy,
            creditCardPrincipalName,
            creditCardAccounting,
            creditCardGroupName,
            creditCardProviderUniqueId,
            creditCardMaskedCardNumber,
            creditCardFirstName,
            creditCardLastName,
            creditCardCompanyName,
            creditCardEmail,
            creditCardPhone,
            creditCardFax,
            creditCardCustomerTaxId,
            creditCardStreetAddress1,
            creditCardStreetAddress2,
            creditCardCity,
            creditCardState,
            creditCardPostalCode,
            creditCardCountryCode,
            creditCardComments,
            new Timestamp(authorizationTime),
            authorizationUsername,
            authorizationPrincipalName
        );

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.CREDIT_CARD_TRANSACTIONS, accounting, InvalidateList.allServers, false);
        return pkey;
    }

    public static void creditCardTransactionSaleCompleted(
        MasterDatabaseConnection conn,
        RequestSource source,
        InvalidateList invalidateList,
        int pkey,
        String authorizationCommunicationResult,
        String authorizationProviderErrorCode,
        String authorizationErrorCode,
        String authorizationProviderErrorMessage,
        String authorizationProviderUniqueId,
        String providerApprovalResult,
        String approvalResult,
        String providerDeclineReason,
        String declineReason,
        String providerReviewReason,
        String reviewReason,
        String providerCvvResult,
        String cvvResult,
        String providerAvsResult,
        String avsResult,
        String approvalCode,
        long captureTime,
        String capturePrincipalName,
        String captureCommunicationResult,
        String captureProviderErrorCode,
        String captureErrorCode,
        String captureProviderErrorMessage,
        String captureProviderUniqueId,
        String status
    ) throws IOException, SQLException {
        BusinessHandler.checkPermission(conn, source, "creditCardTransactionSaleCompleted", AOServPermission.Permission.credit_card_transaction_sale_completed);
        checkAccessCreditCardTransaction(conn, source, "creditCardTransactionSaleCompleted", pkey);
        if(capturePrincipalName!=null) UsernameHandler.checkAccessUsername(conn, source, "creditCardTransactionSaleCompleted", capturePrincipalName);

        String processor = getCreditCardProcessorForCreditCardTransaction(conn, pkey);
        String accounting = getBusinessForCreditCardProcessor(conn, processor);

        creditCardTransactionSaleCompleted(
            conn,
            invalidateList,
            pkey,
            authorizationCommunicationResult,
            authorizationProviderErrorCode,
            authorizationErrorCode,
            authorizationProviderErrorMessage,
            authorizationProviderUniqueId,
            providerApprovalResult,
            approvalResult,
            providerDeclineReason,
            declineReason,
            providerReviewReason,
            reviewReason,
            providerCvvResult,
            cvvResult,
            providerAvsResult,
            avsResult,
            approvalCode,
            captureTime,
            source.getUsername(),
            capturePrincipalName,
            captureCommunicationResult,
            captureProviderErrorCode,
            captureErrorCode,
            captureProviderErrorMessage,
            captureProviderUniqueId,
            status
        );
    }

    public static void creditCardTransactionSaleCompleted(
        MasterDatabaseConnection conn,
        InvalidateList invalidateList,
        int pkey,
        String authorizationCommunicationResult,
        String authorizationProviderErrorCode,
        String authorizationErrorCode,
        String authorizationProviderErrorMessage,
        String authorizationProviderUniqueId,
        String providerApprovalResult,
        String approvalResult,
        String providerDeclineReason,
        String declineReason,
        String providerReviewReason,
        String reviewReason,
        String providerCvvResult,
        String cvvResult,
        String providerAvsResult,
        String avsResult,
        String approvalCode,
        long captureTime,
        String captureUsername,
        String capturePrincipalName,
        String captureCommunicationResult,
        String captureProviderErrorCode,
        String captureErrorCode,
        String captureProviderErrorMessage,
        String captureProviderUniqueId,
        String status
    ) throws IOException, SQLException {
        String processor = getCreditCardProcessorForCreditCardTransaction(conn, pkey);
        String accounting = getBusinessForCreditCardProcessor(conn, processor);

        int updated = conn.executeUpdate(
            "update\n"
            + "  credit_card_transactions\n"
            + "set\n"
            + "  authorization_communication_result=?,\n"
            + "  authorization_provider_error_code=?,\n"
            + "  authorization_error_code=?,\n"
            + "  authorization_provider_error_message=?,\n"
            + "  authorization_provider_unique_id=?,\n"
            + "  authorization_provider_approval_result=?,\n"
            + "  authorization_approval_result=?,\n"
            + "  authorization_provider_decline_reason=?,\n"
            + "  authorization_decline_reason=?,\n"
            + "  authorization_provider_review_reason=?,\n"
            + "  authorization_review_reason=?,\n"
            + "  authorization_provider_cvv_result=?,\n"
            + "  authorization_cvv_result=?,\n"
            + "  authorization_provider_avs_result=?,\n"
            + "  authorization_avs_result=?,\n"
            + "  authorization_approval_code=?,\n"
            + "  capture_time=?::timestamp,\n"
            + "  capture_username=?,\n"
            + "  capture_principal_name=?,\n"
            + "  capture_communication_result=?,\n"
            + "  capture_provider_error_code=?,\n"
            + "  capture_error_code=?,\n"
            + "  capture_provider_error_message=?,\n"
            + "  capture_provider_unique_id=?,\n"
            + "  status=?\n"
            + "where\n"
            + "  pkey=?\n"
            + "  and status='PROCESSING'",
            authorizationCommunicationResult,
            authorizationProviderErrorCode,
            authorizationErrorCode,
            authorizationProviderErrorMessage,
            authorizationProviderUniqueId,
            providerApprovalResult,
            approvalResult,
            providerDeclineReason,
            declineReason,
            providerReviewReason,
            reviewReason,
            providerCvvResult,
            cvvResult,
            providerAvsResult,
            avsResult,
            approvalCode,
            captureTime==0 ? null : new Timestamp(captureTime),
            captureUsername,
            capturePrincipalName,
            captureCommunicationResult,
            captureProviderErrorCode,
            captureErrorCode,
            captureProviderErrorMessage,
            captureProviderUniqueId,
            status,
            pkey
        );
        if(updated!=1) throw new SQLException("Unable to find credit_card_transactions with pkey="+pkey+" and status='PROCESSING'");

        // Notify all clients of the update
        invalidateList.addTable(conn, SchemaTable.TableID.CREDIT_CARD_TRANSACTIONS, accounting, InvalidateList.allServers, false);
    }

    private static class AutomaticPayment {
        final private String accounting;
        final private BigDecimal amount;
        final private int ccPkey;
        final private String ccCardInfo;
        final private String ccPrincipalName;
        final private String ccGroupName;
        final private String ccProviderUniqueId;
        final private String ccFirstName;
        final private String ccLastName;
        final private String ccCompanyName;
        final private String ccEmail;
        final private String ccPhone;
        final private String ccFax;
        final private String ccCustomerTaxId;
        final private String ccStreetAddress1;
        final private String ccStreetAddress2;
        final private String ccCity;
        final private String ccState;
        final private String ccPostalCode;
        final private String ccCountryCode;
        final private String ccComments;
        final private String ccpProviderId;
        final private String ccpClassName;
        final private String ccpParam1;
        final private String ccpParam2;
        final private String ccpParam3;
        final private String ccpParam4;
        
        private AutomaticPayment(
            String accounting,
            BigDecimal amount,
            int ccPkey,
            String ccCardInfo,
            String ccPrincipalName,
            String ccGroupName,
            String ccProviderUniqueId,
            String ccFirstName,
            String ccLastName,
            String ccCompanyName,
            String ccEmail,
            String ccPhone,
            String ccFax,
            String ccCustomerTaxId,
            String ccStreetAddress1,
            String ccStreetAddress2,
            String ccCity,
            String ccState,
            String ccPostalCode,
            String ccCountryCode,
            String ccComments,
            String ccpProviderId,
            String ccpClassName,
            String ccpParam1,
            String ccpParam2,
            String ccpParam3,
            String ccpParam4
        ) {
            this.accounting = accounting;
            this.amount = amount;
            this.ccPkey = ccPkey;
            this.ccCardInfo = ccCardInfo;
            this.ccPrincipalName = ccPrincipalName;
            this.ccGroupName = ccGroupName;
            this.ccProviderUniqueId = ccProviderUniqueId;
            this.ccFirstName = ccFirstName;
            this.ccLastName = ccLastName;
            this.ccCompanyName = ccCompanyName;
            this.ccEmail = ccEmail;
            this.ccPhone = ccPhone;
            this.ccFax = ccFax;
            this.ccCustomerTaxId = ccCustomerTaxId;
            this.ccStreetAddress1 = ccStreetAddress1;
            this.ccStreetAddress2 = ccStreetAddress2;
            this.ccCity = ccCity;
            this.ccState = ccState;
            this.ccPostalCode = ccPostalCode;
            this.ccCountryCode = ccCountryCode;
            this.ccComments = ccComments;
            this.ccpProviderId = ccpProviderId;
            this.ccpClassName = ccpClassName;
            this.ccpParam1 = ccpParam1;
            this.ccpParam2 = ccpParam2;
            this.ccpParam3 = ccpParam3;
            this.ccpParam4 = ccpParam4;
        }
    }

    private static void processAutomaticPayments(int month, int year) {
        Profiler.startProfile(Profiler.UNKNOWN, DNSHandler.class, "processAutomaticPayments(int,int)", null);
        try {
            System.err.println("DEBUG: month="+year+"-"+month);
            try {
                ProcessTimer timer=new ProcessTimer(
                    MasterServer.getRandom(),
                    MasterConfiguration.getWarningSmtpServer(),
                    MasterConfiguration.getWarningEmailFrom(),
                    MasterConfiguration.getWarningEmailTo(),
                    "CreditCardHandler - Process Automatic Payments",
                    "Processes the automatic payments for the month",
                    TIMER_MAX_TIME,
                    TIMER_REMINDER_INTERVAL
                );
                try {
                    timer.start();

                    // Find the beginning of the next month (for transaction search)
                    Calendar beginningOfNextMonth = Calendar.getInstance();
                    beginningOfNextMonth.set(Calendar.YEAR, year);
                    beginningOfNextMonth.set(Calendar.MONTH, month-1);
                    beginningOfNextMonth.set(Calendar.DAY_OF_MONTH, 1);
                    beginningOfNextMonth.set(Calendar.HOUR_OF_DAY, 0);
                    beginningOfNextMonth.set(Calendar.MINUTE, 0);
                    beginningOfNextMonth.set(Calendar.SECOND, 0);
                    beginningOfNextMonth.set(Calendar.MILLISECOND, 0);
                    beginningOfNextMonth.add(Calendar.MONTH, 1);

                    // Find the last minute of the current month
                    Calendar lastMinuteOfTheMonth = (Calendar)beginningOfNextMonth.clone();
                    lastMinuteOfTheMonth.add(Calendar.MINUTE, -1);

                    // Start the transaction
                    InvalidateList invalidateList=new InvalidateList();
                    MasterDatabaseConnection conn=(MasterDatabaseConnection)MasterDatabase.getDatabase().createDatabaseConnection();
                    try {
                        boolean connRolledBack=false;
                        try {
                            // Find the accounting code, credit_card pkey, and account balances of all businesses that have a credit card set for automatic payments (and is active)
                            List<AutomaticPayment> automaticPayments = new ArrayList<AutomaticPayment>();
                            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(
                                "select\n"
                                + "  bu.accounting,\n"
                                + "  endofmonth.balance as endofmonth,\n"
                                + "  current.balance as current,\n"
                                + "  cc.pkey,\n"
                                + "  cc.card_info,\n"
                                + "  cc.principal_name,\n"
                                + "  cc.group_name,\n"
                                + "  cc.provider_unique_id,\n"
                                + "  cc.first_name,\n"
                                + "  cc.last_name,\n"
                                + "  cc.company_name,\n"
                                + "  cc.email,\n"
                                + "  cc.phone,\n"
                                + "  cc.fax,\n"
                                + "  cc.customer_tax_id,\n"
                                + "  cc.street_address1,\n"
                                + "  cc.street_address2,\n"
                                + "  cc.city,\n"
                                + "  cc.state,\n"
                                + "  cc.postal_code,\n"
                                + "  cc.country_code,\n"
                                + "  cc.description,\n"
                                + "  ccp.provider_id,\n"
                                + "  ccp.class_name,\n"
                                + "  ccp.param1,\n"
                                + "  ccp.param2,\n"
                                + "  ccp.param3,\n"
                                + "  ccp.param4\n"
                                + "from\n"
                                + "  businesses bu,\n"
                                + "  (\n"
                                + "    select\n"
                                + "      bu.accounting,\n"
                                + "      coalesce(sum(cast((tr.rate*tr.quantity) as decimal(9,2))), 0) as balance\n"
                                + "    from\n"
                                + "      businesses bu\n"
                                + "      left outer join transactions tr on bu.accounting=tr.accounting\n"
                                + "    where\n"
                                + "      tr.payment_confirmed!='N'"
                                + "      and tr.time<?\n"
                                + "    group by\n"
                                + "      bu.accounting\n"
                                + "  ) as endofmonth,\n"
                                + "  (\n"
                                + "    select\n"
                                + "      bu.accounting,\n"
                                + "      coalesce(sum(cast((tr.rate*tr.quantity) as decimal(9,2))), 0) as balance\n"
                                + "    from\n"
                                + "      businesses bu\n"
                                + "      left outer join transactions tr on bu.accounting=tr.accounting\n"
                                + "    where\n"
                                + "      tr.payment_confirmed!='N'"
                                + "    group by\n"
                                + "      bu.accounting\n"
                                + "  ) as current,\n"
                                + "  credit_cards cc,\n"
                                + "  credit_card_processors ccp\n"
                                + "where\n"
                                + "  bu.accounting=cc.accounting"
                                + "  and bu.accounting=endofmonth.accounting\n"
                                + "  and bu.accounting=current.accounting\n"
                                + "  and cc.use_monthly\n"
                                + "  and cc.active\n"
                                + "  and cc.processor_id=ccp.provider_id\n"
                                + "  and ccp.enabled\n"
                                + "order by\n"
                                + "  bu.accounting\n"
                            );
                            try {
                                pstmt.setTimestamp(1, new Timestamp(beginningOfNextMonth.getTimeInMillis()));
                                ResultSet results = pstmt.executeQuery();
                                BigDecimal total = BigDecimal.ZERO;
                                try {
                                    // Look for duplicate accounting codes and report a warning
                                    String lastAccounting = null;
                                    while(results.next()) {
                                        String accounting = results.getString(1);
                                        if(accounting.equals(lastAccounting)) {
                                            MasterServer.reportWarning("More than one credit card marked as automatic for accounting="+accounting+", using the first one found");
                                        } else {
                                            lastAccounting = accounting;
                                            BigDecimal endofmonth = results.getBigDecimal(2);
                                            BigDecimal current = results.getBigDecimal(3);
                                            if(
                                                endofmonth.compareTo(BigDecimal.ZERO)>0
                                                && current.compareTo(BigDecimal.ZERO)>0
                                            ) {
                                                BigDecimal amount = endofmonth.compareTo(current)<=0 ? endofmonth : current;
                                                total = total.add(amount);
                                                automaticPayments.add(
                                                    new AutomaticPayment(
                                                        accounting,
                                                        amount,
                                                        results.getInt(4),
                                                        results.getString(5),
                                                        results.getString(6),
                                                        results.getString(7),
                                                        results.getString(8),
                                                        results.getString(9),
                                                        results.getString(10),
                                                        results.getString(11),
                                                        results.getString(12),
                                                        results.getString(13),
                                                        results.getString(14),
                                                        results.getString(15),
                                                        results.getString(16),
                                                        results.getString(17),
                                                        results.getString(18),
                                                        results.getString(19),
                                                        results.getString(20),
                                                        results.getString(21),
                                                        results.getString(22),
                                                        results.getString(23),
                                                        results.getString(24),
                                                        results.getString(25),
                                                        results.getString(26),
                                                        results.getString(27),
                                                        results.getString(28)
                                                    )
                                                );
                                            }
                                        }
                                    }
                                } finally {
                                    results.close();
                                }
                                System.out.println("Processing a total of $"+total);
                            } catch(SQLException err) {
                                throw new WrappedSQLException(err, pstmt);
                            } finally {
                                pstmt.close();
                            }
                            // Only need to create the persistence once per DB transaction
                            MasterPersistenceMechanism masterPersistenceMechanism = new MasterPersistenceMechanism(conn, invalidateList);
                            for(AutomaticPayment automaticPayment : automaticPayments) {
                                System.out.println("accounting="+automaticPayment.accounting);
                                System.out.println("    amount="+automaticPayment.amount);
                                // Find the processor
                                CreditCardProcessor processor = new CreditCardProcessor(
                                    MerchantServicesProviderFactory.getMerchantServicesProvider(
                                        automaticPayment.ccpProviderId,
                                        automaticPayment.ccpClassName,
                                        automaticPayment.ccpParam1,
                                        automaticPayment.ccpParam2,
                                        automaticPayment.ccpParam3,
                                        automaticPayment.ccpParam4
                                    ),
                                    masterPersistenceMechanism
                                );
                                System.out.println("    processor="+processor.getProviderId());

                                // Add as pending transaction
                                String paymentTypeName;
                                String cardInfo = automaticPayment.ccCardInfo;
                                if(cardInfo.startsWith("34") || cardInfo.startsWith("37")) {
                                    paymentTypeName = PaymentType.AMEX;
                                } else if(cardInfo.startsWith("60")) {
                                    paymentTypeName = PaymentType.DISCOVER;
                                } else if(cardInfo.startsWith("51") || cardInfo.startsWith("52") || cardInfo.startsWith("53") || cardInfo.startsWith("54") || cardInfo.startsWith("55")) {
                                    paymentTypeName = PaymentType.MASTERCARD;
                                } else if(cardInfo.startsWith("4")) {
                                    paymentTypeName = PaymentType.VISA;
                                } else {
                                    paymentTypeName = null;
                                }
                                int transID = TransactionHandler.addTransaction(
                                    conn,
                                    invalidateList,
                                    new Timestamp(lastMinuteOfTheMonth.getTimeInMillis()),
                                    automaticPayment.accounting,
                                    automaticPayment.accounting,
                                    "aoserv",
                                    TransactionType.PAYMENT,
                                    "Monthly automatic billing",
                                    new BigDecimal("1.000"),
                                    automaticPayment.amount.negate(),
                                    paymentTypeName,
                                    cardInfo,
                                    automaticPayment.ccpProviderId,
                                    com.aoindustries.aoserv.client.Transaction.WAITING_CONFIRMATION
                                );
                                conn.commit();

                                // Process payment
                                Transaction transaction = processor.sale(
                                    null,
                                    null,
                                    new TransactionRequest(
                                        Locale.getDefault(),
                                        false,
                                        InetAddress.getLocalHost().getHostAddress(),
                                        120,
                                        Integer.toString(transID),
                                        TransactionRequest.CurrencyCode.USD,
                                        automaticPayment.amount,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        null,
                                        null,
                                        null,
                                        "Monthly automatic billing"
                                    ),
                                    new CreditCard(
                                        Locale.getDefault(),
                                        Integer.toString(automaticPayment.ccPkey),
                                        automaticPayment.ccPrincipalName,
                                        automaticPayment.ccGroupName,
                                        automaticPayment.ccpProviderId,
                                        automaticPayment.ccProviderUniqueId,
                                        null,
                                        automaticPayment.ccCardInfo,
                                        (byte)-1,
                                        (short)-1,
                                        null,
                                        automaticPayment.ccFirstName,
                                        automaticPayment.ccLastName,
                                        automaticPayment.ccCompanyName,
                                        automaticPayment.ccEmail,
                                        automaticPayment.ccPhone,
                                        automaticPayment.ccFax,
                                        null,
                                        automaticPayment.ccCustomerTaxId,
                                        automaticPayment.ccStreetAddress1,
                                        automaticPayment.ccStreetAddress2,
                                        automaticPayment.ccCity,
                                        automaticPayment.ccState,
                                        automaticPayment.ccPostalCode,
                                        automaticPayment.ccCountryCode,
                                        automaticPayment.ccComments
                                    ),
                                    Locale.getDefault()
                                );

                                AuthorizationResult authorizationResult = transaction.getAuthorizationResult();
                                switch(authorizationResult.getCommunicationResult()) {
                                    case LOCAL_ERROR :
                                    case IO_ERROR :
                                    case GATEWAY_ERROR :
                                    {
                                        // Update transaction as failed
                                        //     TODO: Deactivate the card if this is the 3rd consecutive failure
                                        //     TODO: Notify customer
                                        TransactionHandler.transactionDeclined(
                                            conn,
                                            invalidateList,
                                            transID,
                                            Integer.parseInt(transaction.getPersistenceUniqueId())
                                        );
                                        conn.commit();
                                        System.out.println("    Result: Error");
                                        break;
                                    }
                                    case SUCCESS :
                                        // Check approval result
                                        switch(authorizationResult.getApprovalResult()) {
                                            case HOLD :
                                                // Update transaction
                                                TransactionHandler.transactionHeld(
                                                    conn,
                                                    invalidateList,
                                                    transID,
                                                    Integer.parseInt(transaction.getPersistenceUniqueId())
                                                );
                                                conn.commit();
                                                System.out.println("    Result: Hold");
                                                System.out.println("    Review Reason: "+authorizationResult.getReviewReason());
                                                break;
                                            case DECLINED :
                                                // Update transaction as declined
                                                //     TODO: Deactivate the card if this is the 3rd consecutive failure
                                                //     TODO: Notify customer
                                                TransactionHandler.transactionDeclined(
                                                    conn,
                                                    invalidateList,
                                                    transID,
                                                    Integer.parseInt(transaction.getPersistenceUniqueId())
                                                );
                                                conn.commit();
                                                System.out.println("    Result: Declined");
                                                System.out.println("    Decline Reason: "+authorizationResult.getDeclineReason());
                                                break;
                                            case APPROVED :
                                                // Update transaction as successful
                                                TransactionHandler.transactionApproved(
                                                    conn,
                                                    invalidateList,
                                                    transID,
                                                    Integer.parseInt(transaction.getPersistenceUniqueId())
                                                );
                                                System.out.println("    Result: Approved");
                                                break;
                                            default:
                                                throw new RuntimeException("Unexpected value for authorization approval result: "+authorizationResult.getApprovalResult());
                                        }
                                        break;
                                    default:
                                        throw new RuntimeException("Unexpected value for authorization communication result: "+authorizationResult.getCommunicationResult());
                                }
                            }
                        } catch(IOException err) {
                            if(conn.rollbackAndClose()) {
                                connRolledBack=true;
                                // invalidateList=null; Not cleared because some commits happen during processing
                            }
                            throw err;
                        } catch(SQLException err) {
                            if(conn.rollbackAndClose()) {
                                connRolledBack=true;
                                // invalidateList=null; Not cleared because some commits happen during processing
                            }
                            throw err;
                        } finally {
                            if(!connRolledBack && !conn.isClosed()) conn.commit();
                        }
                    } finally {
                        conn.releaseConnection();
                    }
                    /*if(invalidateList!=null)*/ MasterServer.invalidateTables(invalidateList, null);
                } finally {
                    timer.stop();
                }
            } catch(ThreadDeath TD) {
                throw TD;
            } catch(Throwable T) {
                MasterServer.reportError(T, null);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void main(String[] args) {
        if(args.length==1) {
            String arg = args[0];
            // Not y10k compliant
            if(arg.length()==7) {
                if(arg.charAt(4)=='-') {
                    try {
                        int year = Integer.parseInt(arg.substring(0,4));
                        try {
                            int month = Integer.parseInt(arg.substring(5,7));
                            processAutomaticPayments(month, year);
                        } catch(NumberFormatException err) {
                            System.err.println("Unable to parse month");
                            System.exit(5);
                        }
                    } catch(NumberFormatException err) {
                        System.err.println("Unable to parse year");
                        System.exit(4);
                    }
                } else {
                    System.err.println("Unable to find - in month");
                    System.exit(3);
                }
            } else {
                System.err.println("Invalid month");
                System.exit(2);
            }
        } else {
            System.err.println("usage: "+CreditCardHandler.class.getName()+" YYYY-MM");
            System.exit(1);
        }
    }

    /**
     * Runs at 12:12 am on the first day of the month.
     */
    public boolean isCronJobScheduled(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        return
            minute==12
            && hour==0
            && dayOfMonth==1
        ;
    }

    public int getCronJobScheduleMode() {
        return CRON_JOB_SCHEDULE_SKIP;
    }

    public String getCronJobName() {
        return "CreditCardHandler";
    }

    public int getCronJobThreadPriority() {
        return Thread.NORM_PRIORITY+1;
    }

    public void runCronJob(int minute, int hour, int dayOfMonth, int month, int dayOfWeek, int year) {
        Profiler.startProfile(Profiler.UNKNOWN, DNSHandler.class, "runCronJob(int,int,int,int,int,int)", null);
        try {
            // Find last month
            Calendar cal = Calendar.getInstance();
            cal.set(Calendar.YEAR, year);
            cal.set(Calendar.MONTH, month-1);
            cal.set(Calendar.DAY_OF_MONTH, 1);
            cal.set(Calendar.HOUR_OF_DAY, 0);
            cal.set(Calendar.MINUTE, 0);
            cal.set(Calendar.SECOND, 0);
            cal.set(Calendar.MILLISECOND, 0);
            cal.add(Calendar.MONTH, -1);
            // Process for last month
            processAutomaticPayments(cal.get(Calendar.MONTH)+1, cal.get(Calendar.YEAR));
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
