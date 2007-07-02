package com.aoindustries.aoserv.master;

/*
 * Copyright 2000-2007 by AO Industries, Inc.,
 * 816 Azalea Rd, Mobile, Alabama, 36693, U.S.A.
 * All rights reserved.
 */
import com.aoindustries.aoserv.client.AOSHCommand;
import com.aoindustries.aoserv.client.AOServObject;
import com.aoindustries.aoserv.client.AOServProtocol;
import com.aoindustries.aoserv.client.BusinessAdministrator;
import com.aoindustries.aoserv.client.DNSRecord;
import com.aoindustries.aoserv.client.DNSZoneTable;
import com.aoindustries.aoserv.client.InboxAttributes;
import com.aoindustries.aoserv.client.MasterHistory;
import com.aoindustries.aoserv.client.MasterProcess;
import com.aoindustries.aoserv.client.MasterServerStat;
import com.aoindustries.aoserv.client.MasterUser;
import com.aoindustries.aoserv.client.SchemaTable;
import com.aoindustries.aoserv.client.Ticket;
import com.aoindustries.aoserv.client.Transaction;
import com.aoindustries.aoserv.client.TransactionSearchCriteria;
import com.aoindustries.aoserv.daemon.client.GetBackupDataReporter;
import com.aoindustries.email.ErrorMailer;
import com.aoindustries.io.CompressedDataInputStream;
import com.aoindustries.io.CompressedDataOutputStream;
import com.aoindustries.io.FifoFile;
import com.aoindustries.io.FifoFileInputStream;
import com.aoindustries.io.FifoFileOutputStream;
import com.aoindustries.io.unix.UnixFile;
import com.aoindustries.profiler.Profiler;
import com.aoindustries.sql.AOConnectionPool;
import com.aoindustries.sql.DatabaseConnection;
import com.aoindustries.sql.SQLUtility;
import com.aoindustries.util.BufferManager;
import com.aoindustries.util.ErrorHandler;
import com.aoindustries.util.ErrorPrinter;
import com.aoindustries.util.IntArrayList;
import com.aoindustries.util.IntList;
import com.aoindustries.util.SortedArrayList;
import com.aoindustries.util.StringUtility;
import com.aoindustries.util.ThreadUtility;
import com.aoindustries.util.UnixCrypt;
import com.aoindustries.util.WrappedException;
import java.io.IOException;
import java.net.InetAddress;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.Security;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The <code>AOServServer</code> accepts connections from an <code>SimpleAOClient</code>.
 * Once the connection is accepted and authenticated, the server carries out all actions requested
 * by the client while providing the necessary security checks and data filters.
 * <p>
 * This server is completely threaded to handle multiple, simultaneous clients.
 * </p>
 * @author  AO Industries, Inc.
 */
public abstract class MasterServer {

    /**
     * The database values are read the first time this data is needed.
     */
    private static Map<String,MasterUser> masterUsers;
    private static Map<String,List<String>> masterHosts;
    private static Map<String,com.aoindustries.aoserv.client.MasterServer[]> masterServers;

    /**
     * The time the system started up
     */
    private static final long startTime=System.currentTimeMillis();

    /**
     * The central list of all objects that are notified of
     * cache updates.
     */
    private static final List<RequestSource> cacheListeners=new ArrayList<RequestSource>();

    /**
     * The address that this server will bind to.
     */
    protected final String serverBind;

    /**
     * The port that this server will listen on.
     */
    protected final int serverPort;

    /**
     * The last connector ID that was returned.
     */
    private static long lastID=-1;

    private static int concurrency=0;
    private static int maxConcurrency=0;

    private static long requestCount=0;
    private static long totalTime=0;

    /**
     * Creates a new, running <code>AOServServer</code>.
     */
    protected MasterServer(String serverBind, int serverPort) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "<init>(String,int)", null);
        try {
            this.serverBind = serverBind;
            this.serverPort = serverPort;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    private static void addCacheListener(RequestSource source) {
	Profiler.startProfile(Profiler.FAST, MasterServer.class, "addCacheListener(RequestSource)", null);
	try {
            synchronized(cacheListeners) {
                cacheListeners.add(source);
            }
	} finally {
            Profiler.endProfile(Profiler.FAST);
	}
    }

    private static void appendParam(String S, StringBuilder SB) {
        if(S==null) SB.append("null");
        else {
            int len=S.length();
            // Figure out to use quotes or not
            boolean useQuotes=false;
            for(int c=0;c<len;c++) {
                char ch=S.charAt(c);
                if(ch<=' ' || ch=='\'') {
                    useQuotes=true;
                    break;
                }
            }
            if(useQuotes) SB.append('\'');
            for(int c=0;c<len;c++) {
                char ch=S.charAt(c);
                if(ch=='\'') SB.append('\\');
                SB.append(ch);
            }
            if(useQuotes) SB.append('\'');
        }
    }

    /**
     * Gets the interface address this server is listening on.
     */
    final public String getBindAddress() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "getBindAddress()", null);
        try {
            return serverBind;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    public static long getNextConnectorID() {
	Profiler.startProfile(Profiler.FAST, MasterServer.class, "getNextConnectorID()", null);
	try {
	    synchronized(MasterServer.class) {
		long time=System.currentTimeMillis();
		long id;
		if(lastID<time) id=time;
		else id=lastID+1;
		lastID=id;
		return id;
	    }
	} finally {
            Profiler.endProfile(Profiler.FAST);
	}
    }

    /**
     * Gets the interface port this server is listening on.
     */
    final public int getPort() {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "getPort()", null);
        try {
            return serverPort;
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    abstract public String getProtocol();

    private static Random random;
    public static Random getRandom() {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "getRandom()", null);
        try {
	    synchronized(MasterServer.class) {
                String algorithm="SHA1PRNG";
		try {
		    if(random==null) random=SecureRandom.getInstance(algorithm);
		    return random;
		} catch(NoSuchAlgorithmException err) {
		    throw new WrappedException(err, new Object[] {"algorithm="+algorithm});
		}
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static int getRequestConcurrency() {
	Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "getRequestConcurrency()", null);
	try {
            return concurrency;
	} finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
	}
    }

    private static final Object connectionsLock=new Object();
    private static long connections=0;
    protected static void incConnectionCount() {
	Profiler.startProfile(Profiler.FAST, MasterServer.class, "incConnectionCount()", null);
	try {
            synchronized(connectionsLock) {
                connections++;
            }
	} finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }
    public static long getRequestConnections() {
	Profiler.startProfile(Profiler.FAST, MasterServer.class, "getRequestConnections()", null);
	try {
            synchronized(connectionsLock) {
                return connections;
            }
	} finally {
            Profiler.endProfile(Profiler.FAST);
	}
    }

    public static int getRequestMaxConcurrency() {
	Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "getRequestMaxConcurrency()", null);
	try {
            return maxConcurrency;
	} finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
	}
    }

    public static long getRequestTotalTime() {
	Profiler.startProfile(Profiler.FAST, MasterServer.class, "getRequestTotalTime()", null);
	try {
	    synchronized(MasterServer.class) {
		return totalTime;
	    }
	} finally {
            Profiler.endProfile(Profiler.FAST);
	}
    }

    public static long getRequestTransactions() {
	Profiler.startProfile(Profiler.FAST, MasterServer.class, "getRequestTransactions()", null);
	try {
	    synchronized(MasterServer.class) {
		return requestCount;
	    }
	} finally {
            Profiler.endProfile(Profiler.FAST);
	}
    }

    public static long getStartTime() {
	Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "getStartTime()", null);
	try {
            return startTime;
	} finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
	}
    }

    /**
     * Handles a single request and then returns.
     *
     * @return  <code>true</code> if another request could be made on this stream, or
     *          <code>false</code> if this connection should be closed.
     */
    final boolean handleRequest(
        RequestSource source,
        CompressedDataInputStream in,
        CompressedDataOutputStream out,
        MasterProcess process
    ) throws IOException, SQLException {
        // Time is not added for the cache invalidation connection
        boolean addTime=true;

        process.commandCompleted();

        int taskCode=in.readCompressedInt();
        process.commandRunning();
        synchronized(MasterServer.class) {
            int c=++concurrency;
            if(c>maxConcurrency) maxConcurrency=c;
            requestCount++;
        }
        long requestStartTime=System.currentTimeMillis();
        try {
            final boolean done;
            switch(taskCode) {
                case AOServProtocol.LISTEN_CACHES :
                    process.setCommand("listen_caches");
                    synchronized(MasterServer.class) {
                        addTime=false;
                        concurrency--;
                    }
                    // This method normally never leaves for this command
                    try {
                        addCacheListener(source);
                        final MasterDatabaseConnection conn=(MasterDatabaseConnection)MasterDatabase.getDatabase().createDatabaseConnection();
                        try {
                            while(!BusinessHandler.isBusinessAdministratorDisabled(conn, source.getUsername())) {
                                conn.releaseConnection();
                                process.commandSleeping();
                                long endTime=System.currentTimeMillis()+60000;
                                InvalidateCacheEntry ice;
                                synchronized(source) {
                                    while((ice=source.getNextInvalidatedTables())==null) {
                                        long delay=endTime-System.currentTimeMillis();
                                        if(delay<=0 || delay>60000) break;
                                        try {
                                            source.wait(delay);
                                        } catch(InterruptedException err) {
                                            reportWarning(err, null);
                                        }
                                    }
                                }
                                if(ice!=null) {
                                    process.commandRunning();
                                    IntList clientTableIDs=ice.getInvalidateList();
                                    int size=clientTableIDs.size();
                                    out.writeCompressedInt(size);
                                    for(int c=0;c<size;c++) out.writeCompressedInt(clientTableIDs.getInt(c));
                                } else out.writeCompressedInt(-1);
                                out.flush();

                                if(in.readBoolean()) {
                                    if(ice!=null) {
                                        int server=ice.getServer();
                                        Long id=ice.getCacheSyncID();
                                        if(server!=-1 && id!=null) ServerHandler.removeInvalidateSyncEntry(server, id);
                                    }
                                } else throw new IOException("Unexpected invalidate sync response.");
                            }
                        } finally {
                            conn.releaseConnection();
                        }
                    } finally {
                        removeCacheListener(source);
                    }
                    return false;
                case AOServProtocol.PING :
                    process.setCommand(AOSHCommand.PING);
                    out.writeByte(AOServProtocol.DONE);
                    done=true;
                    break;
                case AOServProtocol.QUIT :
                case -1: // EOF
                    process.setCommand("quit");
                    synchronized(MasterServer.class) {
                        addTime=false;
                        concurrency--;
                    }
                    return false;
                case AOServProtocol.TEST_CONNECTION :
                    process.setCommand("test_connection");
                    out.writeByte(AOServProtocol.DONE);
                    done=true;
                    break;
                default :
                    done=false;
            }
            if(!done) {
                // These commands automatically have the try/catch and the database connection releasing
                // And a finally block to reset thread priority
                Thread currentThread=Thread.currentThread();
                try {
                    InvalidateList invalidateList=new InvalidateList();
                    IntArrayList clientInvalidateList=null;
                    byte resp1=-1;

                    int resp2Int=-1;
                    boolean hasResp2Int=false;

                    long resp2Long=-1;
                    boolean hasResp2Long=false;

                    short resp2Short=-1;
                    boolean hasResp2Short=false;

                    String resp2String=null;

                    boolean resp2Boolean=false;
                    boolean hasResp2Boolean=false;
                    
                    InboxAttributes resp2InboxAttributes=null;
                    boolean hasResp2InboxAttributes=false;

                    long[] resp2LongArray=null;
                    boolean hasResp2LongArray=false;

                    String resp3String=null;

                    final boolean sendInvalidateList;
                    final MasterDatabaseConnection conn=(MasterDatabaseConnection)MasterDatabase.getDatabase().createDatabaseConnection();
                    try {
                        final BackupDatabaseConnection backupConn=(BackupDatabaseConnection)BackupDatabase.getDatabase().createDatabaseConnection();
                        try {
                            boolean connRolledBack=false;
                            boolean backupConnRolledBack=false;
                            try {
                                // Stop processing if the account is disabled
                                if(BusinessHandler.isBusinessAdministratorDisabled(conn, source.getUsername())) throw new IOException("BusinessAdministrator disabled: "+source.getUsername());

                                switch(taskCode) {
                                    case AOServProtocol.INVALIDATE_TABLE :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            String server=in.readBoolean()?in.readUTF().trim():null;
                                            process.setCommand(
                                                AOSHCommand.INVALIDATE,
                                                TableHandler.getTableName(
                                                    conn,
                                                    tableID
                                                ),
                                                server
                                            );
                                            TableHandler.invalidate(
                                                conn,
                                                source,
                                                invalidateList,
                                                tableID,
                                                server
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.ADD : {
                                        int clientTableID=in.readCompressedInt();
                                        SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                        if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                        switch(tableID) {
                                            case BUSINESS_ADMINISTRATORS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    String name=in.readUTF().trim();
                                                    String title=in.readBoolean()?in.readUTF().trim():null;
                                                    long birthday=in.readLong();
                                                    boolean isPrivate=in.readBoolean();
                                                    String workPhone=in.readUTF().trim();
                                                    String homePhone=in.readBoolean()?in.readUTF().trim():null;
                                                    String cellPhone=in.readBoolean()?in.readUTF().trim():null;
                                                    String fax=in.readBoolean()?in.readUTF().trim():null;
                                                    String email=in.readUTF().trim();
                                                    String address1=in.readBoolean()?in.readUTF().trim():null;
                                                    String address2=in.readBoolean()?in.readUTF().trim():null;
                                                    String city=in.readBoolean()?in.readUTF().trim():null;
                                                    String state=in.readBoolean()?in.readUTF().trim():null;
                                                    String country=in.readBoolean()?in.readUTF().trim():null;
                                                    String zip=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_BUSINESS_ADMINISTRATOR,
                                                        username,
                                                        name,
                                                        title,
                                                        birthday==BusinessAdministrator.NONE?null:new java.util.Date(birthday),
                                                        isPrivate?Boolean.TRUE:Boolean.FALSE,
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
                                                        zip
                                                    );
                                                    BusinessHandler.addBusinessAdministrator(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        name,
                                                        title,
                                                        birthday,
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
                                                        zip
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case BUSINESS_PROFILES :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    String name=in.readUTF().trim();
                                                    boolean isPrivate=in.readBoolean();
                                                    String phone=in.readUTF().trim();
                                                    String fax=in.readBoolean()?in.readUTF().trim():null;
                                                    String address1=in.readUTF().trim();
                                                    String address2=in.readBoolean()?in.readUTF().trim():null;
                                                    String city=in.readUTF().trim();
                                                    String state=in.readBoolean()?in.readUTF().trim():null;
                                                    String country=in.readUTF().trim();
                                                    String zip=in.readBoolean()?in.readUTF().trim():null;
                                                    boolean sendInvoice=in.readBoolean();
                                                    String billingContact=in.readUTF().trim();
                                                    String billingEmail=in.readUTF().trim();
                                                    String technicalContact=in.readUTF().trim();
                                                    String technicalEmail=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_BUSINESS_PROFILE,
                                                        accounting,
                                                        name,
                                                        isPrivate?Boolean.TRUE:Boolean.FALSE,
                                                        phone,
                                                        fax,
                                                        address1,
                                                        address2,
                                                        city,
                                                        state,
                                                        country,
                                                        zip,
                                                        sendInvoice?Boolean.TRUE:Boolean.FALSE,
                                                        billingContact,
                                                        billingEmail,
                                                        technicalContact,
                                                        technicalEmail
                                                    );
                                                    int pkey=BusinessHandler.addBusinessProfile(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
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
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case BUSINESS_SERVERS :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    int server=in.readCompressedInt();
                                                    boolean can_configure_backup=AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_102)>=0?in.readBoolean():false;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_BUSINESS_SERVER,
                                                        accounting,
                                                        Integer.valueOf(server),
                                                        can_configure_backup?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    int pkey=BusinessHandler.addBusinessServer(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        server,
                                                        can_configure_backup
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case BUSINESSES :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    String contractVersion=in.readBoolean()?in.readUTF().trim():null;
                                                    String defaultServer=in.readUTF().trim();
                                                    String parent=in.readUTF().trim();
                                                    boolean can_add_backup_servers=
                                                        AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_102)>=0
                                                        ?in.readBoolean()
                                                        :false
                                                    ;
                                                    boolean can_add_businesses=in.readBoolean();
                                                    boolean can_see_prices=
                                                        AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_103)>=0
                                                        ?in.readBoolean()
                                                        :true
                                                    ;
                                                    boolean billParent=in.readBoolean();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_BUSINESS,
                                                        accounting,
                                                        contractVersion,
                                                        defaultServer,
                                                        parent,
                                                        can_add_backup_servers?Boolean.TRUE:Boolean.FALSE,
                                                        can_add_businesses?Boolean.TRUE:Boolean.FALSE,
                                                        can_see_prices?Boolean.TRUE:Boolean.FALSE,
                                                        billParent?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    BusinessHandler.addBusiness(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        contractVersion,
                                                        defaultServer,
                                                        parent,
                                                        can_add_backup_servers,
                                                        can_add_businesses,
                                                        can_see_prices,
                                                        billParent
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case CREDIT_CARDS :
                                                {
                                                    // If before version 1.29, do not support add call but read the old values anyway
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_28)<=0) {
                                                        String accounting=in.readUTF().trim();
                                                        byte[] cardNumber=new byte[in.readCompressedInt()]; in.readFully(cardNumber);
                                                        String cardInfo=in.readUTF().trim();
                                                        byte[] expirationMonth=new byte[in.readCompressedInt()]; in.readFully(expirationMonth);
                                                        byte[] expirationYear=new byte[in.readCompressedInt()]; in.readFully(expirationYear);
                                                        byte[] cardholderName=new byte[in.readCompressedInt()]; in.readFully(cardholderName);
                                                        byte[] streetAddress=new byte[in.readCompressedInt()]; in.readFully(streetAddress);
                                                        byte[] city=new byte[in.readCompressedInt()]; in.readFully(city);
                                                        int len=in.readCompressedInt(); byte[] state=len>=0?new byte[len]:null; if(len>=0) in.readFully(state);
                                                        len=in.readCompressedInt(); byte[] zip=len>=0?new byte[len]:null; if(len>=0) in.readFully(zip);
                                                        boolean useMonthly=in.readBoolean();
                                                        String description=in.readBoolean()?in.readUTF().trim():null;
                                                        throw new SQLException("add_credit_card for protocol version "+AOServProtocol.VERSION_1_28+" or older is no longer supported.");
                                                    }
                                                    String accounting = in.readUTF().trim();
                                                    String cardInfo = in.readUTF().trim();
                                                    String processorName = in.readUTF();
                                                    String providerUniqueId = in.readUTF();
                                                    String firstName = in.readUTF().trim();
                                                    String lastName = in.readUTF().trim();
                                                    String companyName = in.readNullUTF();
                                                    String email = in.readNullUTF();
                                                    String phone = in.readNullUTF();
                                                    String fax = in.readNullUTF();
                                                    String customerTaxId = in.readNullUTF();
                                                    String streetAddress1 = in.readUTF();
                                                    String streetAddress2 = in.readNullUTF();
                                                    String city = in.readUTF();
                                                    String state = in.readNullUTF();
                                                    String postalCode = in.readNullUTF();
                                                    String countryCode = in.readUTF();
                                                    String description = in.readNullUTF();
                                                    
                                                    process.setCommand(
                                                        "add_credit_card",
                                                        accounting,
                                                        cardInfo,
                                                        processorName,
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
                                                        description
                                                    );
                                                    int pkey=CreditCardHandler.addCreditCard(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        cardInfo,
                                                        processorName,
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
                                                        description
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case CVS_REPOSITORIES :
                                                {
                                                    int aoServer=in.readCompressedInt();
                                                    String path=in.readUTF().trim();
                                                    int lsa=in.readCompressedInt();
                                                    int lsg=in.readCompressedInt();
                                                    long mode=in.readLong();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_CVS_REPOSITORY,
                                                        Integer.valueOf(aoServer),
                                                        path,
                                                        Integer.valueOf(lsa),
                                                        Integer.valueOf(lsg),
                                                        Long.toOctalString(mode)
                                                    );
                                                    int pkey=CvsHandler.addCvsRepository(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        aoServer,
                                                        path,
                                                        lsa,
                                                        lsg,
                                                        mode
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case DISABLE_LOG :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    String disableReason=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        "add_disable_log",
                                                        accounting,
                                                        disableReason
                                                    );
                                                    int pkey=BusinessHandler.addDisableLog(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        disableReason
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case DNS_RECORDS :
                                                {
                                                    String zone=in.readUTF().trim();
                                                    String domain=in.readUTF().trim();
                                                    String type=in.readUTF().trim();
                                                    int mx_priority=in.readCompressedInt();
                                                    String destination=in.readUTF().trim();
                                                    int ttl=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_DNS_RECORD,
                                                        zone,
                                                        domain,
                                                        type,
                                                        mx_priority==DNSRecord.NO_MX_PRIORITY?null:Integer.valueOf(mx_priority),
                                                        destination,
                                                        ttl==DNSRecord.NO_TTL?null:Integer.valueOf(ttl)
                                                    );
                                                    int pkey=DNSHandler.addDNSRecord(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        zone,
                                                        domain,
                                                        type,
                                                        mx_priority,
                                                        destination,
                                                        ttl
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case DNS_ZONES :
                                                {
                                                    String packageName=in.readUTF().trim();
                                                    String zone=in.readUTF().trim();
                                                    String ip=in.readUTF().trim();
                                                    int ttl=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_DNS_ZONE,
                                                        packageName,
                                                        zone,
                                                        ip,
                                                        Integer.valueOf(ttl)
                                                    );
                                                    DNSHandler.addDNSZone(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        packageName,
                                                        zone,
                                                        ip,
                                                        ttl
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_ADDRESSES :
                                                {
                                                    String address=in.readUTF().trim();
                                                    int domain=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_ADDRESS,
                                                        address,
                                                        Integer.valueOf(domain)
                                                    );
                                                    int pkey=EmailHandler.addEmailAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        address,
                                                        domain
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_DOMAINS :
                                                {
                                                    String domain=in.readUTF().trim();
                                                    int aoServer=in.readCompressedInt();
                                                    String packageName=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_DOMAIN,
                                                        domain,
                                                        Integer.valueOf(aoServer),
                                                        packageName
                                                    );
                                                    int pkey=EmailHandler.addEmailDomain(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        domain,
                                                        aoServer,
                                                        packageName
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_FORWARDING :
                                                {
                                                    int address=in.readCompressedInt();
                                                    String destination=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_FORWARDING,
                                                        Integer.valueOf(address),
                                                        destination
                                                    );
                                                    int pkey=EmailHandler.addEmailForwarding(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        address,
                                                        destination
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_LIST_ADDRESSES :
                                                {
                                                    int address=in.readCompressedInt();
                                                    int email_list=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_LIST_ADDRESS,
                                                        Integer.valueOf(address),
                                                        Integer.valueOf(email_list)
                                                    );
                                                    int pkey=EmailHandler.addEmailListAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        address,
                                                        email_list
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_LISTS :
                                                {
                                                    String path=in.readUTF().trim();
                                                    int linuxServerAccount=in.readCompressedInt();
                                                    int linuxServerGroup=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_LIST,
                                                        path,
                                                        Integer.valueOf(linuxServerAccount),
                                                        Integer.valueOf(linuxServerGroup)
                                                    );
                                                    int pkey=EmailHandler.addEmailList(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        path,
                                                        linuxServerAccount,
                                                        linuxServerGroup
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_PIPE_ADDRESSES :
                                                {
                                                    int address=in.readCompressedInt();
                                                    int pipe=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_PIPE_ADDRESS,
                                                        Integer.valueOf(address),
                                                        Integer.valueOf(pipe)
                                                    );
                                                    int pkey=EmailHandler.addEmailPipeAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        address,
                                                        pipe
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_PIPES :
                                                {
                                                    int ao_server=in.readCompressedInt();
                                                    String path=in.readUTF().trim();
                                                    String packageName=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_PIPE,
                                                        Integer.valueOf(ao_server),
                                                        path,
                                                        packageName
                                                    );
                                                    int pkey=EmailHandler.addEmailPipe(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        ao_server,
                                                        path,
                                                        packageName
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case EMAIL_SMTP_RELAYS :
                                                {
                                                    process.setPriority(Thread.NORM_PRIORITY+1);
                                                    currentThread.setPriority(Thread.NORM_PRIORITY+1);

                                                    String packageName=in.readUTF().trim();
                                                    int aoServer=in.readCompressedInt();
                                                    String host=in.readUTF().trim();
                                                    String type=in.readUTF().trim();
                                                    long duration=in.readLong();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_EMAIL_SMTP_RELAY,
                                                        packageName,
                                                        aoServer==-1?null:Integer.valueOf(aoServer),
                                                        host,
                                                        type,
                                                        Long.valueOf(duration)
                                                    );
                                                    int pkey=EmailHandler.addEmailSmtpRelay(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        packageName,
                                                        aoServer,
                                                        host,
                                                        type,
                                                        duration
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case FAILOVER_FILE_LOG :
                                                {
                                                    int replication=in.readCompressedInt();
                                                    long startTime=in.readLong();
                                                    long endTime=in.readLong();
                                                    int scanned=in.readCompressedInt();
                                                    int updated=in.readCompressedInt();
                                                    long bytes=in.readLong();
                                                    boolean isSuccessful=in.readBoolean();
                                                    process.setCommand(
                                                        "add_failover_file_log",
                                                        Integer.valueOf(replication),
                                                        new java.util.Date(startTime),
                                                        new java.util.Date(endTime),
                                                        Integer.valueOf(scanned),
                                                        Integer.valueOf(updated),
                                                        Long.valueOf(bytes),
                                                        isSuccessful?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    int pkey=FailoverHandler.addFailoverFileLog(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        replication,
                                                        startTime,
                                                        endTime,
                                                        scanned,
                                                        updated,
                                                        bytes,
                                                        isSuccessful
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case FILE_BACKUP_DEVICES :
                                                {
                                                    long device=in.readLong();
                                                    boolean can_backup=in.readBoolean();
                                                    String description=in.readUTF();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_FILE_BACKUP_DEVICE,
                                                        Long.valueOf(device),
                                                        can_backup?Boolean.TRUE:Boolean.FALSE,
                                                        description
                                                    );
                                                    short pkey=BackupHandler.addFileBackupDevice(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        device,
                                                        can_backup,
                                                        description
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Short=pkey;
                                                    hasResp2Short=true;
                                                }
                                                break;
                                            case FILE_BACKUP_SETTINGS :
                                                {
                                                    int server=in.readCompressedInt();
                                                    String path=in.readUTF();
                                                    int packageNum=in.readCompressedInt();
                                                    short backupLevel=in.readShort();
                                                    short backupRetention=in.readShort();
                                                    boolean recurse=in.readBoolean();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_FILE_BACKUP_SETTING,
                                                        Integer.valueOf(server),
                                                        path,
                                                        Integer.valueOf(packageNum),
                                                        Short.valueOf(backupLevel),
                                                        Short.valueOf(backupRetention),
                                                        recurse?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    int pkey=BackupHandler.addFileBackupSetting(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        server,
                                                        path,
                                                        packageNum,
                                                        backupLevel,
                                                        backupRetention,
                                                        recurse
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case FILE_BACKUPS :
                                                {
                                                    process.setPriority(Thread.MIN_PRIORITY+1);
                                                    currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                                    int server=in.readCompressedInt();
                                                    String path=in.readUTF();
                                                    short device=in.readShort();
                                                    long inode=in.readLong();
                                                    int packageNum=in.readCompressedInt();
                                                    long mode=in.readLong();
                                                    int uid=in.readCompressedInt();
                                                    int gid=in.readCompressedInt();
                                                    int backupData=in.readCompressedInt();
                                                    long md5_hi, md5_lo;
                                                    long modifyTime;
                                                    if(UnixFile.isRegularFile(mode)) {
                                                        md5_hi=in.readLong();
                                                        md5_lo=in.readLong();
                                                        modifyTime=in.readLong();
                                                    } else {
                                                        md5_hi=md5_lo=-1;
                                                        modifyTime=-1;
                                                    }
                                                    short backupLevel=in.readShort();
                                                    short backupRetention=in.readShort();
                                                    String symlinkTarget=UnixFile.isSymLink(mode)?in.readUTF():null;
                                                    long deviceID=UnixFile.isBlockDevice(mode) || UnixFile.isCharacterDevice(mode)?in.readLong():-1;
                                                    process.setCommand(
                                                        "add_file_backup",
                                                        Integer.valueOf(server),
                                                        path,
                                                        device==-1?null:Short.valueOf(device),
                                                        inode==-1?null:Long.valueOf(inode),
                                                        Integer.valueOf(packageNum),
                                                        Long.valueOf(mode),
                                                        uid==-1?null:Integer.valueOf(uid),
                                                        gid==-1?null:Integer.valueOf(gid),
                                                        backupData==-1?null:Integer.valueOf(backupData),
                                                        md5_hi==-1?null:Long.valueOf(md5_hi),
                                                        md5_lo==-1?null:Long.valueOf(md5_lo),
                                                        modifyTime==-1?null:new java.util.Date(modifyTime),
                                                        Short.valueOf(backupLevel),
                                                        Short.valueOf(backupRetention),
                                                        symlinkTarget,
                                                        deviceID==-1?null:Long.valueOf(deviceID)
                                                    );
                                                    int pkey=BackupHandler.addFileBackup(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        server,
                                                        path,
                                                        device,
                                                        inode,
                                                        packageNum,
                                                        mode,
                                                        uid,
                                                        gid,
                                                        backupData,
                                                        md5_hi,
                                                        md5_lo,
                                                        modifyTime,
                                                        backupLevel,
                                                        backupRetention,
                                                        symlinkTarget,
                                                        deviceID
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case FILE_BACKUP_STATS :
                                                {
                                                    process.setPriority(Thread.MIN_PRIORITY+1);
                                                    currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                                    int server=in.readCompressedInt();
                                                    long startTime=in.readLong();
                                                    long endTime=in.readLong();
                                                    int scanned=in.readCompressedInt();
                                                    int file_backup_attribute_matches=in.readCompressedInt();
                                                    int not_matched_md5_files=in.readCompressedInt();
                                                    int not_matched_md5_failures=in.readCompressedInt();
                                                    int send_missing_backup_data_files=in.readCompressedInt();
                                                    int send_missing_backup_data_failures=in.readCompressedInt();
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_108)<=0) in.readCompressedInt();
                                                    int temp_files=in.readCompressedInt();
                                                    int temp_send_backup_data_files=in.readCompressedInt();
                                                    int temp_failures=in.readCompressedInt();
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_108)<=0) in.readCompressedInt();
                                                    int added=in.readCompressedInt();
                                                    int deleted=in.readCompressedInt();
                                                    boolean is_successful=in.readBoolean();
                                                    process.setCommand(
                                                        "add_file_backup_stat",
                                                        Integer.valueOf(server),
                                                        new java.util.Date(startTime),
                                                        new java.util.Date(endTime),
                                                        Integer.valueOf(scanned),
                                                        Integer.valueOf(file_backup_attribute_matches),
                                                        Integer.valueOf(not_matched_md5_files),
                                                        Integer.valueOf(not_matched_md5_failures),
                                                        Integer.valueOf(send_missing_backup_data_files),
                                                        Integer.valueOf(send_missing_backup_data_failures),
                                                        Integer.valueOf(temp_files),
                                                        Integer.valueOf(temp_send_backup_data_files),
                                                        Integer.valueOf(temp_failures),
                                                        Integer.valueOf(added),
                                                        Integer.valueOf(deleted),
                                                        is_successful?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    int pkey=BackupHandler.addFileBackupStat(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        server,
                                                        startTime,
                                                        endTime,
                                                        scanned,
                                                        file_backup_attribute_matches,
                                                        not_matched_md5_files,
                                                        not_matched_md5_failures,
                                                        send_missing_backup_data_files,
                                                        send_missing_backup_data_failures,
                                                        temp_files,
                                                        temp_send_backup_data_files,
                                                        temp_failures,
                                                        added,
                                                        deleted,
                                                        is_successful
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case FTP_GUEST_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_FTP_GUEST_USER,
                                                        username
                                                    );
                                                    FTPHandler.addFTPGuestUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_SHARED_TOMCATS :
                                                {
                                                    String name=in.readUTF().trim();
                                                    int aoServer=in.readCompressedInt();
                                                    int version=in.readCompressedInt();
                                                    String linuxServerAccount=in.readUTF().trim();
                                                    String linuxServerGroup=in.readUTF().trim();
                                                    boolean isSecure=in.readBoolean();
                                                    boolean isOverflow=in.readBoolean();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_SHARED_TOMCAT,
                                                        name,
                                                        Integer.valueOf(aoServer),
                                                        Integer.valueOf(version),
                                                        linuxServerAccount,
                                                        linuxServerGroup,
                                                        isSecure?Boolean.TRUE:Boolean.FALSE,
                                                        isOverflow?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    int pkey=HttpdHandler.addHttpdSharedTomcat(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        name,
                                                        aoServer,
                                                        version,
                                                        linuxServerAccount,
                                                        linuxServerGroup,
                                                        isSecure,
                                                        isOverflow,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case HTTPD_JBOSS_SITES :
                                                {
                                                    int aoServer=in.readCompressedInt();
                                                    String siteName=in.readUTF().trim();
                                                    String packageName=in.readUTF().trim();
                                                    String username=in.readUTF().trim();
                                                    String group=in.readUTF().trim();
                                                    String serverAdmin=in.readUTF().trim();
                                                    boolean useApache=in.readBoolean();
                                                    int ipAddress=in.readCompressedInt();
                                                    String primaryHttpHostname=in.readUTF().trim();
                                                    int len=in.readCompressedInt();
                                                    String[] altHttpHostnames=new String[len];
                                                    for(int c=0;c<len;c++) altHttpHostnames[c]=in.readUTF().trim();
                                                    int jBossVersion=in.readCompressedInt();
                                                    String contentSrc=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_JBOSS_SITE,
                                                        Integer.valueOf(aoServer),
                                                        siteName,
                                                        packageName,
                                                        username,
                                                        group,
                                                        serverAdmin,
                                                        useApache?Boolean.TRUE:Boolean.FALSE,
                                                        ipAddress==-1?null:Integer.valueOf(ipAddress),
                                                        primaryHttpHostname,
                                                        altHttpHostnames,
                                                        Integer.valueOf(jBossVersion),
                                                        contentSrc
                                                    );

                                                    int pkey=HttpdHandler.addHttpdJBossSite(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        aoServer,
                                                        siteName,
                                                        packageName,
                                                        username,
                                                        group,
                                                        serverAdmin,
                                                        useApache,
                                                        ipAddress,
                                                        primaryHttpHostname,
                                                        altHttpHostnames,
                                                        jBossVersion,
                                                        contentSrc
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case HTTPD_SITE_AUTHENTICATED_LOCATIONS :
                                                {
                                                    int httpd_site=in.readCompressedInt();
                                                    String path = in.readUTF();
                                                    boolean isRegularExpression = in.readBoolean();
                                                    String authName = in.readUTF();
                                                    String authGroupFile = in.readUTF();
                                                    String authUserFile = in.readUTF();
                                                    String require = in.readUTF();
                                                    process.setCommand(
                                                        "add_httpd_site_authenticated_location",
                                                        Integer.valueOf(httpd_site),
                                                        path,
                                                        isRegularExpression?Boolean.TRUE:Boolean.FALSE,
                                                        authName,
                                                        authGroupFile,
                                                        authUserFile,
                                                        require
                                                    );
                                                    int pkey=HttpdHandler.addHttpdSiteAuthenticatedLocation(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        httpd_site,
                                                        path,
                                                        isRegularExpression,
                                                        authName,
                                                        authGroupFile,
                                                        authUserFile,
                                                        require
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;							
                                            case HTTPD_SITE_URLS :
                                                {
                                                    int hsb_pkey=in.readCompressedInt();
                                                    String hostname=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_SITE_URL,
                                                        Integer.valueOf(hsb_pkey),
                                                        hostname
                                                    );
                                                    int pkey=HttpdHandler.addHttpdSiteURL(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        hsb_pkey,
                                                        hostname
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case HTTPD_TOMCAT_CONTEXTS :
                                                {
                                                    int tomcat_site=in.readCompressedInt();
                                                    String className=in.readBoolean()?in.readUTF().trim():null;
                                                    boolean cookies=in.readBoolean();
                                                    boolean crossContext=in.readBoolean();
                                                    String docBase=in.readUTF().trim();
                                                    boolean override=in.readBoolean();
                                                    String path=in.readUTF().trim();
                                                    boolean privileged=in.readBoolean();
                                                    boolean reloadable=in.readBoolean();
                                                    boolean useNaming=in.readBoolean();
                                                    String wrapperClass=in.readBoolean()?in.readUTF().trim():null;
                                                    int debug=in.readCompressedInt();
                                                    String workDir=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_TOMCAT_CONTEXT,
                                                        Integer.valueOf(tomcat_site),
                                                        className,
                                                        cookies?Boolean.TRUE:Boolean.FALSE,
                                                        crossContext?Boolean.TRUE:Boolean.FALSE,
                                                        docBase,
                                                        override?Boolean.TRUE:Boolean.FALSE,
                                                        path,
                                                        privileged?Boolean.TRUE:Boolean.FALSE,
                                                        reloadable?Boolean.TRUE:Boolean.FALSE,
                                                        useNaming?Boolean.TRUE:Boolean.FALSE,
                                                        wrapperClass,
                                                        Integer.valueOf(debug),
                                                        workDir
                                                    );
                                                    int pkey=HttpdHandler.addHttpdTomcatContext(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        tomcat_site,
                                                        className,
                                                        cookies,
                                                        crossContext,
                                                        docBase,
                                                        override,
                                                        path,
                                                        privileged,
                                                        reloadable,
                                                        useNaming,
                                                        wrapperClass,
                                                        debug,
                                                        workDir
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;							
                                            case HTTPD_TOMCAT_DATA_SOURCES :
                                                {
                                                    int tomcat_context=in.readCompressedInt();
                                                    String name=in.readUTF();
                                                    String driverClassName=in.readUTF();
                                                    String url=in.readUTF();
                                                    String username=in.readUTF();
                                                    String password=in.readUTF();
                                                    int maxActive=in.readCompressedInt();
                                                    int maxIdle=in.readCompressedInt();
                                                    int maxWait=in.readCompressedInt();
                                                    String validationQuery=in.readUTF();
                                                    if(validationQuery.length()==0) validationQuery=null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_TOMCAT_DATA_SOURCE,
                                                        tomcat_context,
                                                        name,
                                                        driverClassName,
                                                        url,
                                                        username,
                                                        AOServProtocol.FILTERED,
                                                        maxActive,
                                                        maxIdle,
                                                        maxWait,
                                                        validationQuery
                                                    );
                                                    int pkey=HttpdHandler.addHttpdTomcatDataSource(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        tomcat_context,
                                                        name,
                                                        driverClassName,
                                                        url,
                                                        username,
                                                        password,
                                                        maxActive,
                                                        maxIdle,
                                                        maxWait,
                                                        validationQuery
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;							
                                            case HTTPD_TOMCAT_PARAMETERS :
                                                {
                                                    int tomcat_context=in.readCompressedInt();
                                                    String name=in.readUTF();
                                                    String value=in.readUTF();
                                                    boolean override=in.readBoolean();
                                                    String description=in.readUTF();
                                                    if(description.length()==0) description=null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_TOMCAT_PARAMETER,
                                                        tomcat_context,
                                                        name,
                                                        value,
                                                        override,
                                                        description
                                                    );
                                                    int pkey=HttpdHandler.addHttpdTomcatParameter(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        tomcat_context,
                                                        name,
                                                        value,
                                                        override,
                                                        description
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;							
                                            case HTTPD_TOMCAT_SHARED_SITES :
                                                {
                                                    int aoServer=in.readCompressedInt();
                                                    String siteName=in.readUTF().trim();
                                                    String packageName=in.readUTF().trim();
                                                    String username=in.readUTF().trim();
                                                    String group=in.readUTF().trim();
                                                    String serverAdmin=in.readUTF().trim();
                                                    boolean useApache=in.readBoolean();
                                                    int ipAddress=in.readCompressedInt();
                                                    String primaryHttpHostname=in.readUTF().trim();
                                                    int len=in.readCompressedInt();
                                                    String[] altHttpHostnames=new String[len];
                                                    for(int c=0;c<len;c++) altHttpHostnames[c]=in.readUTF().trim();
                                                    String sharedTomcatName=in.readBoolean()?in.readUTF().trim():null;
                                                    int version=in.readCompressedInt();
                                                    String contentSrc=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_TOMCAT_SHARED_SITE,
                                                        Integer.valueOf(aoServer),
                                                        siteName,
                                                        packageName,
                                                        username,
                                                        group,
                                                        serverAdmin,
                                                        useApache?Boolean.TRUE:Boolean.FALSE,
                                                        ipAddress==-1?null:Integer.valueOf(ipAddress),
                                                        primaryHttpHostname,
                                                        altHttpHostnames,
                                                        sharedTomcatName,
                                                        version==-1?null:Integer.valueOf(version),
                                                        contentSrc
                                                    );

                                                    int pkey=HttpdHandler.addHttpdTomcatSharedSite(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        aoServer,
                                                        siteName,
                                                        packageName,
                                                        username,
                                                        group,
                                                        serverAdmin,
                                                        useApache,
                                                        ipAddress,
                                                        primaryHttpHostname,
                                                        altHttpHostnames,
                                                        sharedTomcatName,
                                                        version,
                                                        contentSrc
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case HTTPD_TOMCAT_STD_SITES :
                                                {
                                                    int aoServer=in.readCompressedInt();
                                                    String siteName=in.readUTF().trim();
                                                    String packageName=in.readUTF().trim();
                                                    String username=in.readUTF().trim();
                                                    String group=in.readUTF().trim();
                                                    String serverAdmin=in.readUTF().trim();
                                                    boolean useApache=in.readBoolean();
                                                    int ipAddress=in.readCompressedInt();
                                                    String primaryHttpHostname=in.readUTF().trim();
                                                    int len=in.readCompressedInt();
                                                    String[] altHttpHostnames=new String[len];
                                                    for(int c=0;c<len;c++) altHttpHostnames[c]=in.readUTF().trim();
                                                    int tomcatVersion=in.readCompressedInt();
                                                    String contentSrc=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_HTTPD_TOMCAT_STD_SITE,
                                                        Integer.valueOf(aoServer),
                                                        siteName,
                                                        packageName,
                                                        username,
                                                        group,
                                                        serverAdmin,
                                                        useApache?Boolean.TRUE:Boolean.FALSE,
                                                        ipAddress==-1?null:Integer.valueOf(ipAddress),
                                                        primaryHttpHostname,
                                                        altHttpHostnames,
                                                        Integer.valueOf(tomcatVersion),
                                                        contentSrc
                                                    );
                                                    int pkey=HttpdHandler.addHttpdTomcatStdSite(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        aoServer,
                                                        siteName,
                                                        packageName,
                                                        username,
                                                        group,
                                                        serverAdmin,
                                                        useApache,
                                                        ipAddress,
                                                        primaryHttpHostname,
                                                        altHttpHostnames,
                                                        tomcatVersion,
                                                        contentSrc
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;							
                                            case INCOMING_PAYMENTS :
                                                {
                                                    int transid=in.readCompressedInt();
                                                    byte[] cardName=new byte[in.readCompressedInt()]; in.readFully(cardName);
                                                    byte[] cardNumber=new byte[in.readCompressedInt()]; in.readFully(cardNumber);
                                                    byte[] expMonth=new byte[in.readCompressedInt()]; in.readFully(expMonth);
                                                    byte[] expYear=new byte[in.readCompressedInt()]; in.readFully(expYear);
                                                    process.setCommand(
                                                        AOSHCommand.ADD_INCOMING_PAYMENT,
                                                        Integer.valueOf(transid),
                                                        AOServProtocol.FILTERED,
                                                        AOServProtocol.FILTERED,
                                                        AOServProtocol.FILTERED,
                                                        AOServProtocol.FILTERED
                                                    );
                                                    TransactionHandler.addIncomingPayment(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        transid,
                                                        cardName,
                                                        cardNumber,
                                                        expMonth,
                                                        expYear
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INTERBASE_DATABASES :
                                                {
                                                    String name=in.readUTF().trim();
                                                    int dbGroup=in.readCompressedInt();
                                                    int datdba=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_INTERBASE_DATABASE,
                                                        name,
                                                        Integer.valueOf(dbGroup),
                                                        Integer.valueOf(datdba)
                                                    );
                                                    int pkey=InterBaseHandler.addInterBaseDatabase(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        name,
                                                        dbGroup,
                                                        datdba
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case INTERBASE_DB_GROUPS :
                                                {
                                                    String name=in.readUTF();
                                                    int lsg=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_INTERBASE_DB_GROUP,
                                                        name,
                                                        Integer.valueOf(lsg)
                                                    );
                                                    int pkey=InterBaseHandler.addInterBaseDBGroup(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        name,
                                                        lsg
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case INTERBASE_SERVER_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    int aoServer=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_INTERBASE_SERVER_USER,
                                                        username,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    int pkey=InterBaseHandler.addInterBaseServerUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        aoServer
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case INTERBASE_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    String firstname=in.readBoolean()?in.readUTF().trim():null;
                                                    String middlename=in.readBoolean()?in.readUTF().trim():null;
                                                    String lastname=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_INTERBASE_USER,
                                                        username,
                                                        firstname,
                                                        middlename,
                                                        lastname
                                                    );
                                                    InterBaseHandler.addInterBaseUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        firstname,
                                                        middlename,
                                                        lastname
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_ACC_ADDRESSES :
                                                {
                                                    int address=in.readCompressedInt();
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_LINUX_ACC_ADDRESS,
                                                        Integer.valueOf(address),
                                                        username
                                                    );
                                                    int pkey=EmailHandler.addLinuxAccAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        address,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case LINUX_ACCOUNTS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    String primary_group=in.readUTF().trim();
                                                    String name=in.readUTF().trim();
                                                    String office_location=in.readBoolean()?in.readUTF().trim():null;
                                                    String office_phone=in.readBoolean()?in.readUTF().trim():null;
                                                    String home_phone=in.readBoolean()?in.readUTF().trim():null;
                                                    String type=in.readUTF().trim();
                                                    String shell=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_LINUX_ACCOUNT,
                                                        username,
                                                        primary_group,
                                                        name,
                                                        office_location,
                                                        office_phone,
                                                        home_phone,
                                                        type,
                                                        shell
                                                    );
                                                    LinuxAccountHandler.addLinuxAccount(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        primary_group,
                                                        name,
                                                        office_location,
                                                        office_phone,
                                                        home_phone,
                                                        type,
                                                        shell,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_GROUP_ACCOUNTS :
                                                {
                                                    String groupName=in.readUTF().trim();
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_LINUX_GROUP_ACCOUNT,
                                                        groupName,
                                                        username
                                                    );
                                                    int pkey=LinuxAccountHandler.addLinuxGroupAccount(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        groupName,
                                                        username,
                                                        false,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case LINUX_GROUPS :
                                                {
                                                    String groupName=in.readUTF().trim();
                                                    String packageName=in.readUTF().trim();
                                                    String type=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_LINUX_GROUP,
                                                        groupName,
                                                        packageName,
                                                        type
                                                    );
                                                    LinuxAccountHandler.addLinuxGroup(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        groupName,
                                                        packageName,
                                                        type,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_SERVER_ACCOUNTS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    int aoServer=in.readCompressedInt();
                                                    String home=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_LINUX_SERVER_ACCOUNT,
                                                        username,
                                                        Integer.valueOf(aoServer),
                                                        home
                                                    );
                                                    int pkey=LinuxAccountHandler.addLinuxServerAccount(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        aoServer,
                                                        home,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case LINUX_SERVER_GROUPS :
                                                {
                                                    String groupName=in.readUTF().trim();
                                                    int aoServer=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_LINUX_SERVER_GROUP,
                                                        groupName,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    int pkey=LinuxAccountHandler.addLinuxServerGroup(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        groupName,
                                                        aoServer,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case MAJORDOMO_LISTS :
                                                {
                                                    int majordomoServer=in.readCompressedInt();
                                                    String listName=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_MAJORDOMO_LIST,
                                                        Integer.valueOf(majordomoServer),
                                                        listName
                                                    );
                                                    int pkey=EmailHandler.addMajordomoList(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        majordomoServer,
                                                        listName
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case MAJORDOMO_SERVERS :
                                                {
                                                    int emailDomain=in.readCompressedInt();
                                                    int lsa=in.readCompressedInt();
                                                    int lsg=in.readCompressedInt();
                                                    String version=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_MAJORDOMO_SERVER,
                                                        Integer.valueOf(emailDomain),
                                                        Integer.valueOf(lsa),
                                                        Integer.valueOf(lsg),
                                                        version
                                                    );
                                                    EmailHandler.addMajordomoServer(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        emailDomain,
                                                        lsa,
                                                        lsg,
                                                        version
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MYSQL_DATABASES :
                                                {
                                                    String name=in.readUTF().trim();
                                                    int mysqlServer=in.readCompressedInt();
                                                    String packageName=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_MYSQL_DATABASE,
                                                        name,
                                                        Integer.valueOf(mysqlServer),
                                                        packageName
                                                    );
                                                    int pkey=MySQLHandler.addMySQLDatabase(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        name,
                                                        mysqlServer,
                                                        packageName
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case MYSQL_DB_USERS :
                                                {
                                                    int mysql_database=in.readCompressedInt();
                                                    int mysql_server_user=in.readCompressedInt();
                                                    boolean canSelect=in.readBoolean();
                                                    boolean canInsert=in.readBoolean();
                                                    boolean canUpdate=in.readBoolean();
                                                    boolean canDelete=in.readBoolean();
                                                    boolean canCreate=in.readBoolean();
                                                    boolean canDrop=in.readBoolean();
                                                    boolean canIndex=in.readBoolean();
                                                    boolean canAlter=in.readBoolean();
                                                    boolean canCreateTempTable;
                                                    boolean canLockTables;
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_111)>=0) {
                                                        canCreateTempTable=in.readBoolean();
                                                        canLockTables=in.readBoolean();
                                                    } else {
                                                        canCreateTempTable=false;
                                                        canLockTables=false;
                                                    }
                                                    boolean canCreateView;
                                                    boolean canShowView;
                                                    boolean canCreateRoutine;
                                                    boolean canAlterRoutine;
                                                    boolean canExecute;
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_4)>=0) {
                                                        canCreateView=in.readBoolean();
                                                        canShowView=in.readBoolean();
                                                        canCreateRoutine=in.readBoolean();
                                                        canAlterRoutine=in.readBoolean();
                                                        canExecute=in.readBoolean();
                                                    } else {
                                                        canCreateView=false;
                                                        canShowView=false;
                                                        canCreateRoutine=false;
                                                        canAlterRoutine=false;
                                                        canExecute=false;
                                                    }
                                                    process.setCommand(
                                                        AOSHCommand.ADD_MYSQL_DB_USER,
                                                        mysql_database,
                                                        mysql_server_user,
                                                        canSelect,
                                                        canInsert,
                                                        canUpdate,
                                                        canDelete,
                                                        canCreate,
                                                        canDrop,
                                                        canIndex,
                                                        canAlter,
                                                        canCreateTempTable,
                                                        canLockTables,
                                                        canCreateView,
                                                        canShowView,
                                                        canCreateRoutine,
                                                        canAlterRoutine,
                                                        canExecute
                                                    );
                                                    int pkey=MySQLHandler.addMySQLDBUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        mysql_database,
                                                        mysql_server_user,
                                                        canSelect,
                                                        canInsert,
                                                        canUpdate,
                                                        canDelete,
                                                        canCreate,
                                                        canDrop,
                                                        canIndex,
                                                        canAlter,
                                                        canCreateTempTable,
                                                        canLockTables,
                                                        canCreateView,
                                                        canShowView,
                                                        canCreateRoutine,
                                                        canAlterRoutine,
                                                        canExecute
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case MYSQL_SERVER_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    int mysqlServer=in.readCompressedInt();
                                                    String host=in.readBoolean()?in.readUTF().trim():null;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_MYSQL_SERVER_USER,
                                                        username,
                                                        Integer.valueOf(mysqlServer),
                                                        host
                                                    );
                                                    int pkey=MySQLHandler.addMySQLServerUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        mysqlServer,
                                                        host
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case MYSQL_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_MYSQL_USER,
                                                        username
                                                    );
                                                    MySQLHandler.addMySQLUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case NET_BINDS :
                                                {
                                                    int aoServer=in.readCompressedInt();
                                                    String packageName=in.readUTF().trim();
                                                    int ipAddress=in.readCompressedInt();
                                                    int port=in.readCompressedInt();
                                                    String netProtocol=in.readUTF().trim();
                                                    String appProtocol=in.readUTF().trim();
                                                    boolean openFirewall=in.readBoolean();
                                                    boolean monitoringEnabled;
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_103)<=0) {
                                                        monitoringEnabled=in.readCompressedInt()!=-1;
                                                        if(in.readBoolean()) in.readUTF();
                                                        if(in.readBoolean()) in.readUTF();
                                                        if(in.readBoolean()) in.readUTF();
                                                    } else monitoringEnabled=in.readBoolean();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_NET_BIND,
                                                        Integer.valueOf(aoServer),
                                                        packageName,
                                                        Integer.valueOf(ipAddress),
                                                        Integer.valueOf(port),
                                                        netProtocol,
                                                        appProtocol,
                                                        openFirewall?Boolean.TRUE:Boolean.FALSE,
                                                        monitoringEnabled?Boolean.TRUE:Boolean.FALSE
                                                    );
                                                    int pkey=NetBindHandler.addNetBind(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        aoServer,
                                                        packageName,
                                                        ipAddress,
                                                        port,
                                                        netProtocol,
                                                        appProtocol,
                                                        openFirewall,
                                                        monitoringEnabled
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case NOTICE_LOG :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    String billingContact=in.readUTF().trim();
                                                    String emailAddress=in.readUTF().trim();
                                                    int balance=in.readCompressedInt();
                                                    String type=in.readUTF().trim();
                                                    int transid=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_NOTICE_LOG,
                                                        accounting,
                                                        billingContact,
                                                        emailAddress,
                                                        SQLUtility.getDecimal(balance),
                                                        type,
                                                        Integer.valueOf(transid)
                                                    );
                                                    BusinessHandler.addNoticeLog(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        billingContact,
                                                        emailAddress,
                                                        balance,
                                                        type,
                                                        transid
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case PACKAGES :
                                                {
                                                    String packageName=in.readUTF().trim();
                                                    String accounting=in.readUTF().trim();
                                                    int packageDefinition;
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_122)<=0) {
                                                        // Try to find a package definition owned by the source accounting with matching rates and limits
                                                        String level=in.readUTF().trim();
                                                        int rate=in.readCompressedInt();
                                                        int userLimit=in.readCompressedInt();
                                                        int additionalUserRate=in.readCompressedInt();
                                                        int popLimit=in.readCompressedInt();
                                                        int additionalPopRate=in.readCompressedInt();
                                                        String baAccounting=UsernameHandler.getBusinessForUsername(conn, source.getUsername());
                                                        packageDefinition=PackageHandler.findActivePackageDefinition(
                                                            conn,
                                                            baAccounting,
                                                            rate,
                                                            userLimit,
                                                            popLimit
                                                        );
                                                        if(packageDefinition==-1) {
                                                            throw new SQLException(
                                                                "Unable to find PackageDefinition: accounting="
                                                                + baAccounting
                                                                + ", rate="
                                                                + SQLUtility.getDecimal(rate)
                                                                + ", userLimit="
                                                                + (userLimit==-1?"unlimited":Integer.toString(userLimit))
                                                                + ", popLimit="
                                                                + (popLimit==-1?"unlimited":Integer.toString(popLimit))
                                                            );
                                                        }
                                                    } else {
                                                        packageDefinition=in.readCompressedInt();
                                                    }
                                                    process.setCommand(
                                                        AOSHCommand.ADD_PACKAGE,
                                                        packageName,
                                                        accounting,
                                                        Integer.valueOf(packageDefinition)
                                                    );
                                                    int pkey=PackageHandler.addPackage(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        packageName,
                                                        accounting,
                                                        packageDefinition
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case PACKAGE_DEFINITIONS :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    String category=in.readUTF().trim();
                                                    String name=in.readUTF().trim();
                                                    String version=in.readUTF().trim();
                                                    String display=in.readUTF().trim();
                                                    String description=in.readUTF().trim();
                                                    int setupFee=in.readCompressedInt();
                                                    String setupFeeTransactionType=in.readBoolean()?in.readUTF():null;
                                                    int monthlyRate=in.readCompressedInt();
                                                    String monthlyRateTransactionType=in.readUTF();
                                                    process.setCommand(
                                                        "add_package_definition",
                                                        accounting,
                                                        category,
                                                        name,
                                                        version,
                                                        display,
                                                        description,
                                                        SQLUtility.getDecimal(setupFee),
                                                        setupFeeTransactionType,
                                                        SQLUtility.getDecimal(monthlyRate),
                                                        monthlyRateTransactionType
                                                    );
                                                    int pkey=PackageHandler.addPackageDefinition(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        category,
                                                        name,
                                                        version,
                                                        display,
                                                        description,
                                                        setupFee,
                                                        setupFeeTransactionType,
                                                        monthlyRate,
                                                        monthlyRateTransactionType
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case POSTGRES_DATABASES :
                                                {
                                                    String name=in.readUTF().trim();
                                                    int postgresServer=in.readCompressedInt();
                                                    int datdba=in.readCompressedInt();
                                                    int encoding=in.readCompressedInt();
                                                    boolean enable_postgis=AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_27)>=0?in.readBoolean():false;
                                                    process.setCommand(
                                                        AOSHCommand.ADD_POSTGRES_DATABASE,
                                                        name,
                                                        Integer.valueOf(postgresServer),
                                                        Integer.valueOf(datdba),
                                                        Integer.valueOf(encoding),
                                                        enable_postgis
                                                    );
                                                    int pkey=PostgresHandler.addPostgresDatabase(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        name,
                                                        postgresServer,
                                                        datdba,
                                                        encoding,
                                                        enable_postgis
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case POSTGRES_SERVER_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    int postgresServer=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_POSTGRES_SERVER_USER,
                                                        username,
                                                        Integer.valueOf(postgresServer)
                                                    );
                                                    int pkey=PostgresHandler.addPostgresServerUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username,
                                                        postgresServer
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case POSTGRES_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_POSTGRES_USER,
                                                        username
                                                    );
                                                    PostgresHandler.addPostgresUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case SENDMAIL_SMTP_STATS :
                                                {
                                                    process.setPriority(Thread.NORM_PRIORITY-1);
                                                    currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                                    String packageName=in.readUTF().trim();
                                                    long date=in.readLong();
                                                    int aoServer=in.readCompressedInt();
                                                    int in_count=in.readCompressedInt();
                                                    long in_bandwidth=in.readLong();
                                                    int out_count=in.readCompressedInt();
                                                    long out_bandwidth=in.readLong();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_SENDMAIL_SMTP_STAT,
                                                        packageName,
                                                        SQLUtility.getDate(date),
                                                        Integer.valueOf(aoServer),
                                                        Integer.valueOf(in_count),
                                                        Long.valueOf(in_bandwidth),
                                                        Integer.valueOf(out_count),
                                                        Long.valueOf(out_bandwidth)
                                                    );
                                                    int pkey=EmailHandler.addSendmailSmtpStat(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        packageName,
                                                        date,
                                                        aoServer,
                                                        in_count,
                                                        in_bandwidth,
                                                        out_count,
                                                        out_bandwidth
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case SIGNUP_REQUESTS :
                                                {
                                                    String accounting = in.readUTF();
                                                    String ip_address = in.readUTF();
                                                    int package_definition = in.readCompressedInt();
                                                    String business_name = in.readUTF();
                                                    String business_phone = in.readUTF();
                                                    String business_fax = in.readBoolean() ? in.readUTF() : null;
                                                    String business_address1 = in.readUTF();
                                                    String business_address2 = in.readBoolean() ? in.readUTF() : null;
                                                    String business_city = in.readUTF();
                                                    String business_state = in.readBoolean() ? in.readUTF() : null;
                                                    String business_country = in.readUTF();
                                                    String business_zip = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_name = in.readUTF();
                                                    String ba_title = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_work_phone = in.readUTF();
                                                    String ba_cell_phone = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_home_phone = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_fax = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_email = in.readUTF();
                                                    String ba_address1 = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_address2 = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_city = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_state = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_country = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_zip = in.readBoolean() ? in.readUTF() : null;
                                                    String ba_username = in.readUTF();
                                                    String billing_contact = in.readUTF();
                                                    String billing_email = in.readUTF();
                                                    boolean billing_use_monthly = in.readBoolean();
                                                    boolean billing_pay_one_year = in.readBoolean();
                                                    // Encrypted values
                                                    int recipient = in.readCompressedInt();
                                                    String ciphertext = in.readUTF();
                                                    // options
                                                    int numOptions = in.readCompressedInt();
                                                    Map<String,String> options = new HashMap<String,String>(numOptions * 4 / 3 + 1);
                                                    for(int c=0;c<numOptions;c++) {
                                                        String name = in.readUTF();
                                                        String value = in.readBoolean() ? in.readUTF() : null;
                                                        options.put(name, value);
                                                    }
                                                    process.setCommand(
                                                        "add_signup_request",
                                                        accounting,
                                                        ip_address,
                                                        package_definition,
                                                        business_name,
                                                        business_phone,
                                                        business_fax,
                                                        business_address1,
                                                        business_address2,
                                                        business_city,
                                                        business_state,
                                                        business_country,
                                                        business_zip,
                                                        ba_name,
                                                        ba_title,
                                                        ba_work_phone,
                                                        ba_cell_phone,
                                                        ba_home_phone,
                                                        ba_fax,
                                                        ba_email,
                                                        ba_address1,
                                                        ba_address2,
                                                        ba_city,
                                                        ba_state,
                                                        ba_country,
                                                        ba_zip,
                                                        ba_username,
                                                        billing_contact,
                                                        billing_email,
                                                        billing_use_monthly,
                                                        billing_pay_one_year,
                                                        // Encrypted values
                                                        recipient,
                                                        ciphertext,
                                                        // options
                                                        numOptions
                                                    );
                                                    int pkey=SignupHandler.addSignupRequest(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        ip_address,
                                                        package_definition,
                                                        business_name,
                                                        business_phone,
                                                        business_fax,
                                                        business_address1,
                                                        business_address2,
                                                        business_city,
                                                        business_state,
                                                        business_country,
                                                        business_zip,
                                                        ba_name,
                                                        ba_title,
                                                        ba_work_phone,
                                                        ba_cell_phone,
                                                        ba_home_phone,
                                                        ba_fax,
                                                        ba_email,
                                                        ba_address1,
                                                        ba_address2,
                                                        ba_city,
                                                        ba_state,
                                                        ba_country,
                                                        ba_zip,
                                                        ba_username,
                                                        billing_contact,
                                                        billing_email,
                                                        billing_use_monthly,
                                                        billing_pay_one_year,
                                                        // Encrypted values
                                                        recipient,
                                                        ciphertext,
                                                        // options
                                                        options
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case SPAM_EMAIL_MESSAGES :
                                                {
                                                    int esr=in.readCompressedInt();
                                                    String message=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_SPAM_EMAIL_MESSAGE,
                                                        Integer.valueOf(esr),
                                                        message
                                                    );
                                                    int pkey=EmailHandler.addSpamEmailMessage(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        esr,
                                                        message
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case TICKETS :
                                                {
                                                    String accounting;
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_126)>=0) {
                                                        accounting=in.readBoolean()?in.readUTF().trim():null;
                                                    } else {
                                                        String packageName=in.readUTF().trim();
                                                        accounting=PackageHandler.getBusinessForPackage(conn, packageName);
                                                    }
                                                    String username=in.readUTF().trim();
                                                    String type=in.readUTF().trim();
                                                    String details=in.readUTF().trim();
                                                    long deadline=in.readLong();
                                                    String clientPriority=in.readUTF().trim();
                                                    String adminPriority=in.readUTF().trim();
                                                    if(adminPriority.length()==0) adminPriority=null;
                                                    String technology=in.readBoolean()?in.readUTF().trim():null;
                                                    String assignedTo;
                                                    String contactEmails;
                                                    String contactPhoneNumbers;
                                                    if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_125)>=0) {
                                                        assignedTo=in.readBoolean()?in.readUTF().trim():null;
                                                        contactEmails=in.readUTF();
                                                        contactPhoneNumbers=in.readUTF();
                                                    } else {
                                                        assignedTo=null;
                                                        contactEmails="";
                                                        contactPhoneNumbers="";
                                                    }
                                                    process.setCommand(
                                                        AOSHCommand.ADD_TICKET,
                                                        accounting,
                                                        username,
                                                        type,
                                                        details,
                                                        deadline==Ticket.NO_DEADLINE?null:new java.util.Date(deadline),
                                                        clientPriority,
                                                        adminPriority,
                                                        technology,
                                                        assignedTo,
                                                        contactEmails,
                                                        contactPhoneNumbers
                                                    );
                                                    int pkey=TicketHandler.addTicket(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        username,
                                                        type,
                                                        details,
                                                        deadline,
                                                        clientPriority,
                                                        adminPriority,
                                                        technology,
                                                        assignedTo,
                                                        contactEmails,
                                                        contactPhoneNumbers
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case TRANSACTIONS :
                                                {
                                                    String accounting=in.readUTF().trim();
                                                    String sourceAccounting=in.readUTF().trim();
                                                    String business_administrator=in.readUTF().trim();
                                                    String type=in.readUTF().trim();
                                                    String description=in.readUTF().trim();
                                                    int quantity=in.readCompressedInt();
                                                    int rate=in.readCompressedInt();
                                                    String paymentType=in.readBoolean()?in.readUTF():null;
                                                    String paymentInfo=in.readBoolean()?in.readUTF():null;
                                                    byte payment_confirmed=in.readByte();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_TRANSACTION,
                                                        accounting,
                                                        sourceAccounting,
                                                        business_administrator,
                                                        type,
                                                        description,
                                                        SQLUtility.getMilliDecimal(quantity),
                                                        SQLUtility.getDecimal(rate),
                                                        paymentType,
                                                        paymentInfo,
                                                        payment_confirmed==Transaction.CONFIRMED?"Y"
                                                        :payment_confirmed==Transaction.NOT_CONFIRMED?"N"
                                                        :"W"
                                                    );
                                                    int pkey=TransactionHandler.addTransaction(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        accounting,
                                                        sourceAccounting,
                                                        business_administrator,
                                                        type,
                                                        description,
                                                        quantity,
                                                        rate,
                                                        paymentType,
                                                        paymentInfo,
                                                        payment_confirmed
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                    resp2Int=pkey;
                                                    hasResp2Int=true;
                                                }
                                                break;
                                            case USERNAMES :
                                                {
                                                    String packageName=in.readUTF().trim();
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.ADD_USERNAME,
                                                        packageName,
                                                        username
                                                    );
                                                    UsernameHandler.addUsername(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        packageName,
                                                        username,
                                                        false
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            default :
                                                throw new IOException("Unknown table ID for add: clientTableID="+clientTableID+", tableID="+tableID);
                                        }
                                        sendInvalidateList=true;
                                        break;
                                    }
                                    case AOServProtocol.ADD_BACKUP_SERVER :
                                        {
                                            String hostname=in.readUTF();
                                            String farm=in.readUTF();
                                            int owner=in.readCompressedInt();
                                            String description=in.readUTF();
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_107)<=0) in.readUTF();
                                            int backup_hour=in.readCompressedInt();
                                            int os_version=in.readCompressedInt();
                                            String username=in.readUTF();
                                            String password=in.readUTF();
                                            String contact_phone=in.readUTF();
                                            String contact_email=in.readUTF();
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_107)<=0) throw new IOException("addBackupServer call not supported for AOServ Client version <= "+AOServProtocol.VERSION_1_0_A_107+", please upgrade AOServ Client.");
                                            process.setCommand(
                                                AOSHCommand.ADD_BACKUP_SERVER,
                                                hostname,
                                                farm,
                                                Integer.valueOf(owner),
                                                description,
                                                Integer.valueOf(backup_hour),
                                                Integer.valueOf(os_version),
                                                username,
                                                AOServProtocol.FILTERED,
                                                contact_phone,
                                                contact_email
                                            );
                                            int pkey=ServerHandler.addBackupServer(
                                                conn,
                                                source,
                                                invalidateList,
                                                hostname,
                                                farm,
                                                owner,
                                                description,
                                                backup_hour,
                                                os_version,
                                                username,
                                                password,
                                                contact_phone,
                                                contact_email
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=pkey;
                                            hasResp2Int=true;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.ADD_FILE_BACKUPS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            int batchSize=in.readCompressedInt();
                                            String[] paths=new String[batchSize];
                                            short[] devices=new short[batchSize];
                                            long[] inodes=new long[batchSize];
                                            int[] packages=new int[batchSize];
                                            long[] modes=new long[batchSize];
                                            int[] uids=new int[batchSize];
                                            int[] gids=new int[batchSize];
                                            int[] backupDatas=new int[batchSize];
                                            long[] md5_his=new long[batchSize];
                                            long[] md5_los=new long[batchSize];
                                            long[] modifyTimes=new long[batchSize];
                                            short[] backupLevels=new short[batchSize];
                                            short[] backupRetentions=new short[batchSize];
                                            String[] symlinkTargets=new String[batchSize];
                                            long[] deviceIDs=new long[batchSize];
                                            for(int c=0;c<batchSize;c++) {
                                                paths[c]=in.readCompressedUTF();
                                                devices[c]=in.readShort();
                                                inodes[c]=in.readLong();
                                                packages[c]=in.readCompressedInt();
                                                long mode=modes[c]=in.readLong();
                                                uids[c]=in.readCompressedInt();
                                                gids[c]=in.readCompressedInt();
                                                backupDatas[c]=in.readCompressedInt();
                                                if(UnixFile.isRegularFile(mode)) {
                                                    md5_his[c]=in.readLong();
                                                    md5_los[c]=in.readLong();
                                                    modifyTimes[c]=in.readLong();
                                                } else {
                                                    md5_his[c]=md5_los[c]=-1;
                                                    modifyTimes[c]=-1;
                                                }
                                                backupLevels[c]=in.readShort();
                                                backupRetentions[c]=in.readShort();
                                                symlinkTargets[c]=UnixFile.isSymLink(mode)?in.readCompressedUTF():null;
                                                deviceIDs[c]=UnixFile.isBlockDevice(mode) || UnixFile.isCharacterDevice(mode)?in.readLong():-1;
                                            }
                                            if(batchSize==1) {
                                                process.setCommand(
                                                    "add_file_backups",
                                                    Integer.valueOf(server),
                                                    Integer.valueOf(batchSize),
                                                    paths[0],
                                                    devices[0]==-1?null:Short.valueOf(devices[0]),
                                                    inodes[0]==-1?null:Long.valueOf(inodes[0]),
                                                    Integer.valueOf(packages[0]),
                                                    Long.valueOf(modes[0]),
                                                    uids[0]==-1?null:Integer.valueOf(uids[0]),
                                                    gids[0]==-1?null:Integer.valueOf(gids[0]),
                                                    backupDatas[0]==-1?null:Integer.valueOf(backupDatas[0]),
                                                    md5_his[0]==-1?null:Long.valueOf(md5_his[0]),
                                                    md5_los[0]==-1?null:Long.valueOf(md5_los[0]),
                                                    modifyTimes[0]==-1?null:new java.util.Date(modifyTimes[0]),
                                                    Short.valueOf(backupLevels[0]),
                                                    Short.valueOf(backupRetentions[0]),
                                                    symlinkTargets[0],
                                                    deviceIDs[0]==-1?null:Long.valueOf(deviceIDs[0])
                                                );
                                            } else {
                                                process.setCommand(
                                                    "add_file_backups",
                                                    Integer.valueOf(server),
                                                    Integer.valueOf(batchSize),
                                                    paths[0],
                                                    devices[0]==-1?null:Short.valueOf(devices[0]),
                                                    inodes[0]==-1?null:Long.valueOf(inodes[0]),
                                                    Integer.valueOf(packages[0]),
                                                    Long.valueOf(modes[0]),
                                                    uids[0]==-1?null:Integer.valueOf(uids[0]),
                                                    gids[0]==-1?null:Integer.valueOf(gids[0]),
                                                    backupDatas[0]==-1?null:Integer.valueOf(backupDatas[0]),
                                                    md5_his[0]==-1?null:Long.valueOf(md5_his[0]),
                                                    md5_los[0]==-1?null:Long.valueOf(md5_los[0]),
                                                    modifyTimes[0]==-1?null:new java.util.Date(modifyTimes[0]),
                                                    Short.valueOf(backupLevels[0]),
                                                    Short.valueOf(backupRetentions[0]),
                                                    symlinkTargets[0],
                                                    deviceIDs[0]==-1?null:Long.valueOf(deviceIDs[0]),
                                                    "..."
                                                );
                                            }
                                            int startPKey=BackupHandler.addFileBackups(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                server,
                                                batchSize,
                                                paths,
                                                devices,
                                                inodes,
                                                packages,
                                                modes,
                                                uids,
                                                gids,
                                                backupDatas,
                                                md5_his,
                                                md5_los,
                                                modifyTimes,
                                                backupLevels,
                                                backupRetentions,
                                                symlinkTargets,
                                                deviceIDs
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=startPKey;
                                            hasResp2Int=true;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.ADD_MASTER_ENTROPY :
                                        {
                                            int numBytes=in.readCompressedInt();
                                            byte[] entropy=new byte[numBytes];
                                            for(int c=0;c<numBytes;c++) entropy[c]=in.readByte();
                                            process.setCommand(
                                                "add_master_entropy",
                                                Integer.valueOf(numBytes)
                                            );
                                            RandomHandler.addMasterEntropy(conn, source, entropy);
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.BACKUP_INTERBASE_DATABASE :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int id=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.BACKUP_INTERBASE_DATABASE,
                                                Integer.valueOf(id)
                                            );
                                            int pkey=InterBaseHandler.backupInterBaseDatabase(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                id
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=pkey;
                                            hasResp2Int=true;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.BACKUP_MYSQL_DATABASE :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int md=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.BACKUP_MYSQL_DATABASE,
                                                Integer.valueOf(md)
                                            );
                                            int pkey=MySQLHandler.backupMySQLDatabase(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                md
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=pkey;
                                            hasResp2Int=true;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.BACKUP_POSTGRES_DATABASE :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int pd=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.BACKUP_POSTGRES_DATABASE,
                                                Integer.valueOf(pd)
                                            );
                                            int pkey=PostgresHandler.backupPostgresDatabase(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                pd
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=pkey;
                                            hasResp2Int=true;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.BOUNCE_TICKET :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(AOSHCommand.BOUNCE_TICKET);
                                            TicketHandler.bounceTicket(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CANCEL_BUSINESS :
                                        {
                                            String accounting=in.readUTF().trim();
                                            String cancelReason=in.readBoolean()?in.readUTF().trim():null;
                                            process.setCommand(AOSHCommand.CANCEL_BUSINESS, accounting, cancelReason);
                                            BusinessHandler.cancelBusiness(conn, source, invalidateList, accounting, cancelReason);
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CHANGE_TICKET_ADMIN_PRIORITY :
                                        {
                                            int ticketID=in.readCompressedInt(); 
                                            String priority=in.readUTF().trim();
                                            if(priority.length()==0) priority=null;
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.CHANGE_TICKET_ADMIN_PRIORITY,
                                                Integer.valueOf(ticketID),
                                                priority,
                                                username,
                                                comments
                                            );
                                            TicketHandler.changeTicketAdminPriority(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                priority,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CHANGE_TICKET_CLIENT_PRIORITY :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String priority=in.readUTF().trim();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.CHANGE_TICKET_CLIENT_PRIORITY,
                                                Integer.valueOf(ticketID),
                                                priority,
                                                username,
                                                comments
                                            );
                                            TicketHandler.changeTicketClientPriority(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                priority,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CHANGE_TICKET_DEADLINE :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            long deadline=in.readLong();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.CHANGE_TICKET_DEADLINE,
                                                Integer.valueOf(ticketID),
                                                deadline==Ticket.NO_DEADLINE?null:new java.util.Date(deadline),
                                                username,
                                                comments
                                            );
                                            TicketHandler.changeTicketDeadline(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                deadline,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CHANGE_TICKET_TECHNOLOGY :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String technology=in.readBoolean()?in.readUTF().trim():null;
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.CHANGE_TICKET_TECHNOLOGY,
                                                Integer.valueOf(ticketID),
                                                technology,
                                                username,
                                                comments
                                            );
                                            TicketHandler.changeTicketTechnology(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                technology,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CHANGE_TICKET_TYPE :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String type=in.readUTF().trim();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.CHANGE_TICKET_TYPE,
                                                Integer.valueOf(ticketID),
                                                type,
                                                username,
                                                comments
                                            );
                                            TicketHandler.changeTicketType(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                type,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                            break;
                                        }
                                    case AOServProtocol.COMPLETE_TICKET :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.COMPLETE_TICKET,
                                                Integer.valueOf(ticketID),
                                                username,
                                                comments
                                            );
                                            TicketHandler.completeTicket(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.COMPARE_LINUX_SERVER_ACCOUNT_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.COMPARE_LINUX_SERVER_ACCOUNT_PASSWORD,
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            boolean result=LinuxAccountHandler.comparePassword(
                                                conn,
                                                source,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=result;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.COPY_HOME_DIRECTORY :
                                        {
                                            int from_lsa=in.readCompressedInt();
                                            int to_server=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.COPY_HOME_DIRECTORY,
                                                Integer.valueOf(from_lsa),
                                                Integer.valueOf(to_server)
                                            );
                                            long byteCount=LinuxAccountHandler.copyHomeDirectory(
                                                conn,
                                                source,
                                                from_lsa,
                                                to_server
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Long=byteCount;
                                            hasResp2Long=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.COPY_LINUX_SERVER_ACCOUNT_PASSWORD :
                                        {
                                            int from_lsa=in.readCompressedInt();
                                            int to_lsa=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.COPY_LINUX_SERVER_ACCOUNT_PASSWORD,
                                                Integer.valueOf(from_lsa),
                                                Integer.valueOf(to_lsa)
                                            );
                                            LinuxAccountHandler.copyLinuxServerAccountPassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                from_lsa,
                                                to_lsa
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.COPY_PACKAGE_DEFINITION :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                "copy_package_definition",
                                                Integer.valueOf(pkey)
                                            );
                                            int newPKey=PackageHandler.copyPackageDefinition(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=newPKey;
                                            hasResp2Int=true;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.CREDIT_CARD_DECLINED :
                                        {
                                            int transid=in.readCompressedInt();
                                            String reason=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.DECLINE_CREDIT_CARD,
                                                Integer.valueOf(transid),
                                                reason
                                            );
                                            CreditCardHandler.creditCardDeclined(
                                                conn,
                                                source,
                                                invalidateList,
                                                transid,
                                                reason
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.DISABLE :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            int disableLog=in.readCompressedInt();
                                            Integer dlObj=Integer.valueOf(disableLog);
                                            switch(tableID) {
                                                case BUSINESSES :
                                                    {
                                                        String accounting=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_BUSINESS,
                                                            dlObj,
                                                            accounting
                                                        );
                                                        BusinessHandler.disableBusiness(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            accounting
                                                        );
                                                    }
                                                    break;
                                                case BUSINESS_ADMINISTRATORS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_BUSINESS_ADMINISTRATOR,
                                                            dlObj,
                                                            username
                                                        );
                                                        BusinessHandler.disableBusinessAdministrator(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case CVS_REPOSITORIES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_CVS_REPOSITORY,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        CvsHandler.disableCvsRepository(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_LISTS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_EMAIL_LIST,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        EmailHandler.disableEmailList(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_PIPES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_EMAIL_PIPE,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        EmailHandler.disableEmailPipe(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_SMTP_RELAYS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_EMAIL_SMTP_RELAY,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        EmailHandler.disableEmailSmtpRelay(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SHARED_TOMCATS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_HTTPD_SHARED_TOMCAT,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        HttpdHandler.disableHttpdSharedTomcat(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SITES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_HTTPD_SITE,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        HttpdHandler.disableHttpdSite(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SITE_BINDS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_HTTPD_SITE_BIND,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        HttpdHandler.disableHttpdSiteBind(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case INTERBASE_SERVER_USERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_INTERBASE_SERVER_USER,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        InterBaseHandler.disableInterBaseServerUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case INTERBASE_USERS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_INTERBASE_USER,
                                                            dlObj,
                                                            username
                                                        );
                                                        InterBaseHandler.disableInterBaseUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case LINUX_ACCOUNTS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_LINUX_ACCOUNT,
                                                            dlObj,
                                                            username
                                                        );
                                                        LinuxAccountHandler.disableLinuxAccount(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case LINUX_SERVER_ACCOUNTS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_LINUX_SERVER_ACCOUNT,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        LinuxAccountHandler.disableLinuxServerAccount(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case MYSQL_SERVER_USERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_MYSQL_SERVER_USER,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        MySQLHandler.disableMySQLServerUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case MYSQL_USERS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_MYSQL_USER,
                                                            dlObj,
                                                            username
                                                        );
                                                        MySQLHandler.disableMySQLUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case PACKAGES :
                                                    {
                                                        String name=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_PACKAGE,
                                                            dlObj,
                                                            name
                                                        );
                                                        PackageHandler.disablePackage(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            name
                                                        );
                                                    }
                                                    break;
                                                case POSTGRES_SERVER_USERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_POSTGRES_SERVER_USER,
                                                            dlObj,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        PostgresHandler.disablePostgresServerUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case POSTGRES_USERS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_POSTGRES_USER,
                                                            dlObj,
                                                            username
                                                        );
                                                        PostgresHandler.disablePostgresUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case USERNAMES :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.DISABLE_USERNAME,
                                                            dlObj,
                                                            username
                                                        );
                                                        UsernameHandler.disableUsername(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            disableLog,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                default :
                                                    throw new IOException("Unknown table ID for disable: clientTableID="+clientTableID+", tableID="+tableID);
                                            }
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.DUMP_INTERBASE_DATABASE :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.DUMP_INTERBASE_DATABASE,
                                                Integer.valueOf(pkey)
                                            );
                                            InterBaseHandler.dumpInterBaseDatabase(
                                                conn,
                                                source,
                                                out,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.DUMP_MYSQL_DATABASE :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.DUMP_MYSQL_DATABASE,
                                                Integer.valueOf(pkey)
                                            );
                                            MySQLHandler.dumpMySQLDatabase(
                                                conn,
                                                source,
                                                out,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.DUMP_POSTGRES_DATABASE :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.DUMP_POSTGRES_DATABASE,
                                                Integer.valueOf(pkey)
                                            );
                                            PostgresHandler.dumpPostgresDatabase(
                                                conn,
                                                source,
                                                out,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.ENABLE :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            switch(tableID) {
                                                case BUSINESSES :
                                                    {
                                                        String accounting=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_BUSINESS,
                                                            accounting
                                                        );
                                                        BusinessHandler.enableBusiness(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            accounting
                                                        );
                                                    }
                                                    break;
                                                case BUSINESS_ADMINISTRATORS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_BUSINESS_ADMINISTRATOR,
                                                            username
                                                        );
                                                        BusinessHandler.enableBusinessAdministrator(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case CVS_REPOSITORIES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_CVS_REPOSITORY,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        CvsHandler.enableCvsRepository(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_LISTS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_EMAIL_LIST,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        EmailHandler.enableEmailList(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_PIPES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_EMAIL_PIPE,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        EmailHandler.enableEmailPipe(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_SMTP_RELAYS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_EMAIL_SMTP_RELAY,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        EmailHandler.enableEmailSmtpRelay(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SHARED_TOMCATS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_HTTPD_SHARED_TOMCAT,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        HttpdHandler.enableHttpdSharedTomcat(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SITES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_HTTPD_SITE,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        HttpdHandler.enableHttpdSite(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SITE_BINDS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_HTTPD_SITE_BIND,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        HttpdHandler.enableHttpdSiteBind(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case INTERBASE_SERVER_USERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_INTERBASE_SERVER_USER,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        InterBaseHandler.enableInterBaseServerUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case INTERBASE_USERS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_INTERBASE_USER,
                                                            username
                                                        );
                                                        InterBaseHandler.enableInterBaseUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case LINUX_ACCOUNTS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_LINUX_ACCOUNT,
                                                            username
                                                        );
                                                        LinuxAccountHandler.enableLinuxAccount(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case LINUX_SERVER_ACCOUNTS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_LINUX_SERVER_ACCOUNT,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        LinuxAccountHandler.enableLinuxServerAccount(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case MYSQL_SERVER_USERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_MYSQL_SERVER_USER,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        MySQLHandler.enableMySQLServerUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case MYSQL_USERS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_MYSQL_USER,
                                                            username
                                                        );
                                                        MySQLHandler.enableMySQLUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case PACKAGES :
                                                    {
                                                        String name=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_PACKAGE,
                                                            name
                                                        );
                                                        PackageHandler.enablePackage(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            name
                                                        );
                                                    }
                                                    break;
                                                case POSTGRES_SERVER_USERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_POSTGRES_SERVER_USER,
                                                            Integer.valueOf(pkey)
                                                        );
                                                        PostgresHandler.enablePostgresServerUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey
                                                        );
                                                    }
                                                    break;
                                                case POSTGRES_USERS :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_POSTGRES_USER,
                                                            username
                                                        );
                                                        PostgresHandler.enablePostgresUser(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                case USERNAMES :
                                                    {
                                                        String username=in.readUTF().trim();
                                                        process.setCommand(
                                                            AOSHCommand.ENABLE_USERNAME,
                                                            username
                                                        );
                                                        UsernameHandler.enableUsername(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            username
                                                        );
                                                    }
                                                    break;
                                                default :
                                                    throw new IOException("Unknown table ID for enable: clientTableID="+clientTableID+", tableID="+tableID);
                                            }
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.FIND_HARD_LINKS :
                                        {
                                            int server=in.readCompressedInt();
                                            short device=in.readShort();
                                            long inode=in.readLong();
                                            process.setCommand(
                                                "find_hard_links",
                                                Integer.valueOf(server),
                                                Short.valueOf(device),
                                                Long.valueOf(inode)
                                            );
                                            BackupHandler.findHardLinks(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                server,
                                                device,
                                                inode
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.FIND_FILE_BACKUPS_BY_MD5 :
                                        {
                                            long md5_hi=in.readLong();
                                            long md5_lo=in.readLong();
                                            int server=in.readCompressedInt();
                                            process.setCommand(
                                                "find_file_backups_by_md5",
                                                Long.valueOf(md5_hi),
                                                Long.valueOf(md5_lo),
                                                server==-1?null:Integer.valueOf(server)
                                            );
                                            BackupHandler.findFileBackupsByMD5(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                md5_hi,
                                                md5_lo,
                                                server
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.FIND_LATEST_FILE_BACKUP_SET_ATTRIBUTE_MATCHES :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            int batchSize=in.readCompressedInt();
                                            String[] paths=new String[batchSize];
                                            short[] devices=new short[batchSize];
                                            long[] inodes=new long[batchSize];
                                            int[] packages=new int[batchSize];
                                            long[] modes=new long[batchSize];
                                            int[] uids=new int[batchSize];
                                            int[] gids=new int[batchSize];
                                            long[] modify_times=new long[batchSize];
                                            short[] backup_levels=new short[batchSize];
                                            short[] backup_retentions=new short[batchSize];
                                            long[] lengths=new long[batchSize];
                                            String[] symlink_targets=new String[batchSize];
                                            long[] device_ids=new long[batchSize];
                                            for(int c=0;c<batchSize;c++) {
                                                paths[c]=in.readCompressedUTF();
                                                devices[c]=in.readShort();
                                                inodes[c]=in.readLong();
                                                packages[c]=in.readCompressedInt();
                                                long mode=modes[c]=in.readLong();
                                                uids[c]=in.readCompressedInt();
                                                gids[c]=in.readCompressedInt();
                                                backup_levels[c]=in.readShort();
                                                backup_retentions[c]=in.readShort();
                                                if(UnixFile.isRegularFile(mode)) {
                                                    modify_times[c]=in.readLong();
                                                    lengths[c]=in.readLong();
                                                } else {
                                                    modify_times[c]=-1;
                                                    lengths[c]=-1;
                                                }
                                                if(UnixFile.isSymLink(mode)) symlink_targets[c]=in.readCompressedUTF();
                                                else symlink_targets[c]=null;
                                                if(UnixFile.isBlockDevice(mode) || UnixFile.isCharacterDevice(mode)) device_ids[c]=in.readLong();
                                                else device_ids[c]=-1;
                                            }
                                            if(batchSize>0) {
                                                process.setCommand(
                                                    "find_latest_file_backup_set_attribute_matches",
                                                    Integer.valueOf(server),
                                                    Integer.valueOf(batchSize),
                                                    paths[0],
                                                    devices[0]==-1?null:Short.valueOf(devices[0]),
                                                    inodes[0]==-1?null:Long.valueOf(inodes[0]),
                                                    "..."
                                                );
                                            } else {
                                                process.setCommand(
                                                    "find_latest_file_backup_set_attribute_matches",
                                                    Integer.valueOf(server),
                                                    Integer.valueOf(batchSize)
                                                );
                                            }
                                            Object[] OA=BackupHandler.findLatestFileBackupSetAttributeMatches(
                                                conn,
                                                backupConn,
                                                source,
                                                server,
                                                batchSize,
                                                paths,
                                                devices,
                                                inodes,
                                                packages,
                                                modes,
                                                uids,
                                                gids,
                                                modify_times,
                                                backup_levels,
                                                backup_retentions,
                                                lengths,
                                                symlink_targets,
                                                device_ids
                                            );
                                            int[] fileBackups=(int[])OA[0];
                                            int[] backupDatas=(int[])OA[1];
                                            long[] md5_his=(long[])OA[2];
                                            long[] md5_los=(long[])OA[3];
                                            boolean[] hasDatas=(boolean[])OA[4];

                                            out.writeByte(AOServProtocol.DONE);
                                            for(int c=0;c<batchSize;c++) {
                                                int fileBackup=fileBackups[c];
                                                out.writeCompressedInt(fileBackup);
                                                if(
                                                    fileBackup!=-1
                                                    && UnixFile.isRegularFile(modes[c])
                                                ) {
                                                    out.writeCompressedInt(backupDatas[c]);
                                                    out.writeLong(md5_his[c]);
                                                    out.writeLong(md5_los[c]);
                                                    out.writeBoolean(hasDatas[c]);
                                                }
                                            }
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.FIND_OR_ADD_BACKUP_DATA :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            long length=in.readLong();
                                            long md5_hi=in.readLong();
                                            long md5_lo=in.readLong();
                                            process.setCommand(
                                                "find_or_add_backup_data",
                                                Integer.valueOf(server),
                                                Long.valueOf(length),
                                                Long.valueOf(md5_hi),
                                                Long.valueOf(md5_lo)
                                            );
                                            Object[] OA=BackupHandler.findOrAddBackupData(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                server,
                                                length,
                                                md5_hi,
                                                md5_lo
                                            );
                                            out.writeByte(AOServProtocol.DONE);
                                            out.writeCompressedInt(((Integer)OA[0]).intValue());
                                            out.writeBoolean(((Boolean)OA[1]).booleanValue());
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.FIND_OR_ADD_BACKUP_DATAS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            int batchSize=in.readCompressedInt();
                                            long[] lengths=new long[batchSize];
                                            long[] md5_his=new long[batchSize];
                                            long[] md5_los=new long[batchSize];
                                            for(int c=0;c<batchSize;c++) {
                                                lengths[c]=in.readLong();
                                                md5_his[c]=in.readLong();
                                                md5_los[c]=in.readLong();
                                            }
                                            process.setCommand(
                                                "find_or_add_backup_datas",
                                                Integer.valueOf(server),
                                                Integer.valueOf(batchSize),
                                                "..."
                                            );
                                            Object[] OA=BackupHandler.findOrAddBackupDatas(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                server,
                                                batchSize,
                                                lengths,
                                                md5_his,
                                                md5_los
                                            );
                                            int[] pkeys=(int[])OA[0];
                                            boolean[] hasDatas=(boolean[])OA[1];

                                            out.writeByte(AOServProtocol.DONE);
                                            for(int c=0;c<batchSize;c++) {
                                                out.writeCompressedInt(pkeys[c]);
                                                out.writeBoolean(hasDatas[c]);
                                            }
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.FLAG_BACKUP_DATA_AS_STORED :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);
                                            
                                            int backupData=in.readCompressedInt();
                                            boolean isCompressed = in.readBoolean();
                                            long compressedSize=isCompressed ? in.readLong() : -1;
                                            if(isCompressed) {
                                                process.setCommand(
                                                    "flag_backup_data_as_stored",
                                                    Integer.valueOf(backupData),
                                                    Boolean.valueOf(isCompressed),
                                                    Long.valueOf(compressedSize)
                                                );
                                            } else {
                                                process.setCommand(
                                                    "flag_backup_data_as_stored",
                                                    Integer.valueOf(backupData),
                                                    Boolean.valueOf(isCompressed)
                                                );
                                            }
                                            BackupHandler.flagBackupDataAsStored(
                                                conn,
                                                backupConn,
                                                source,
                                                backupData,
                                                isCompressed,
                                                compressedSize
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.FLAG_FILE_BACKUPS_AS_DELETED :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int batchSize=in.readCompressedInt();
                                            int[] pkeys=new int[batchSize];
                                            for(int c=0;c<batchSize;c++) pkeys[c]=in.readCompressedInt();
                                            if(batchSize==0) {
                                                process.setCommand(
                                                    "flag_file_backups_as_deleted",
                                                    Integer.valueOf(batchSize)
                                                );
                                            } else if(batchSize==1) {
                                                process.setCommand(
                                                    "flag_file_backups_as_deleted",
                                                    Integer.valueOf(batchSize),
                                                    Integer.valueOf(pkeys[0])
                                                );
                                            } else {
                                                process.setCommand(
                                                    "flag_file_backups_as_deleted",
                                                    Integer.valueOf(batchSize),
                                                    Integer.valueOf(pkeys[0]),
                                                    "..."
                                                );
                                            }
                                            BackupHandler.flagFileBackupsAsDeleted(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                batchSize,
                                                pkeys
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_ACCOUNTING_CODE :
                                        {
                                            String template=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_ACCOUNTING,
                                                template
                                            );
                                            String accounting=BusinessHandler.generateAccountingCode(
                                                conn,
                                                template
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=accounting;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_INTERBASE_DATABASE_NAME :
                                        {
                                            int dbGroup=in.readCompressedInt();
                                            String template_base=in.readUTF().trim();
                                            String template_added=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_INTERBASE_DATABASE_NAME,
                                                Integer.valueOf(dbGroup),
                                                template_base,
                                                template_added
                                            );
                                            String name=InterBaseHandler.generateInterBaseDatabaseName(
                                                conn,
                                                dbGroup,
                                                template_base,
                                                template_added
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_INTERBASE_DB_GROUP_NAME :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            String template_base=in.readUTF().trim();
                                            String template_added=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_INTERBASE_DB_GROUP_NAME,
                                                Integer.valueOf(aoServer),
                                                template_base,
                                                template_added
                                            );
                                            String name=InterBaseHandler.generateInterBaseDBGroupName(
                                                conn,
                                                aoServer,
                                                template_base,
                                                template_added
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_MYSQL_DATABASE_NAME :
                                        {
                                            String template_base=in.readUTF().trim();
                                            String template_added=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_MYSQL_DATABASE_NAME,
                                                template_base,
                                                template_added
                                            );
                                            String name=MySQLHandler.generateMySQLDatabaseName(
                                                conn,
                                                template_base,
                                                template_added
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_PACKAGE_NAME :
                                        {
                                            String template=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_PACKAGE_NAME,
                                                template
                                            );
                                            String name=PackageHandler.generatePackageName(
                                                conn,
                                                template
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_POSTGRES_DATABASE_NAME :
                                        {
                                            String template_base=in.readUTF().trim();
                                            String template_added=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_POSTGRES_DATABASE_NAME,
                                                template_base,
                                                template_added
                                            );
                                            String name=PostgresHandler.generatePostgresDatabaseName(
                                                conn,
                                                template_base,
                                                template_added
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_SHARED_TOMCAT_NAME :
                                        {
                                            String template=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_SHARED_TOMCAT_NAME,
                                                template
                                            );
                                            String name=HttpdHandler.generateSharedTomcatName(
                                                conn,
                                                template
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GENERATE_SITE_NAME :
                                        {
                                            String template=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GENERATE_SITE_NAME,
                                                template
                                            );
                                            String name=HttpdHandler.generateSiteName(
                                                conn,
                                                template
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=name;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_ACCOUNT_BALANCE :
                                        {
                                            String accounting=in.readUTF().trim();
                                            process.setCommand(
                                                "get_account_balance",
                                                accounting
                                            );
                                            TransactionHandler.getAccountBalance(
                                                conn,
                                                source,
                                                out,
                                                accounting
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_ACCOUNT_BALANCE_BEFORE :
                                        {
                                            String accounting=in.readUTF().trim();
                                            long before=in.readLong();
                                            process.setCommand(
                                                "get_account_balance_before",
                                                accounting,
                                                new java.util.Date(before)
                                            );
                                            TransactionHandler.getAccountBalanceBefore(
                                                conn,
                                                source,
                                                out,
                                                accounting,
                                                before
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BANK_TRANSACTIONS_ACCOUNT :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String accounting=in.readUTF().trim();
                                            process.setCommand(
                                                "get_bank_transactions_account",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                accounting
                                            );
                                            BankAccountHandler.getBankTransactionsAccount(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                accounting
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_CONFIRMED_ACCOUNT_BALANCE :
                                        {
                                            String accounting=in.readUTF().trim();
                                            process.setCommand(
                                                "get_confirmed_account_balance",
                                                accounting
                                            );
                                            TransactionHandler.getConfirmedAccountBalance(
                                                conn,
                                                source,
                                                out,
                                                accounting
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_CONFIRMED_ACCOUNT_BALANCE_BEFORE :
                                        {
                                            String accounting=in.readUTF().trim();
                                            long before=in.readLong();
                                            process.setCommand(
                                                "get_confirmed_account_balance_before",
                                                accounting,
                                                new java.util.Date(before)
                                            );
                                            TransactionHandler.getConfirmedAccountBalanceBefore(
                                                conn,
                                                source,
                                                out,
                                                accounting,
                                                before
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_ACTIONS_TICKET :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                "get_actions_ticket",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                Integer.valueOf(pkey)
                                            );
                                            TicketHandler.getActionsTicket(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_ACTIONS_BUSINESS_ADMINISTRATOR :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                "get_actions_business_administrator",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                username
                                            );
                                            TicketHandler.getActionsBusinessAdministrator(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_AUTORESPONDER_CONTENT :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_AUTORESPONDER_CONTENT,
                                                Integer.valueOf(pkey)
                                            );
                                            String content=LinuxAccountHandler.getAutoresponderContent(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=content;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_AWSTATS_FILE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String path=in.readUTF();
                                            String queryString=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.GET_AWSTATS_FILE,
                                                Integer.valueOf(pkey),
                                                path,
                                                queryString
                                            );
                                            HttpdHandler.getAWStatsFile(
                                                conn,
                                                source,
                                                pkey,
                                                path,
                                                queryString,
                                                out
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BACKUP_DATA :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int pkey=in.readCompressedInt();
                                            long skipBytes=in.readLong();
                                            GetBackupDataReporter reporter=new GetBackupDataReporter();
                                            reporter.setFinishedSize(skipBytes);
                                            process.setCommand(
                                                "get_backup_data",
                                                Integer.valueOf(pkey),
                                                reporter
                                            );
                                            BackupHandler.getBackupDataBytes(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                pkey,
                                                skipBytes,
                                                reporter
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BACKUP_DATAS_FOR_BACKUP_PARTITION :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int backupPartition=in.readCompressedInt();
                                            boolean hasDataOnly=in.readBoolean();
                                            process.setCommand(
                                                "get_backup_datas_for_backup_partition",
                                                Integer.valueOf(backupPartition),
                                                hasDataOnly?Boolean.TRUE:Boolean.FALSE
                                            );
                                            BackupHandler.getBackupDatasForBackupPartition(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                backupPartition,
                                                hasDataOnly
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BACKUP_DATA_PKEYS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            boolean hasDataOnly=in.readBoolean();
                                            short minBackupLevel=in.readShort();
                                            process.setCommand(
                                                "get_backup_data_pkeys",
                                                hasDataOnly?Boolean.TRUE:Boolean.FALSE,
                                                Short.valueOf(minBackupLevel)
                                            );
                                            BackupHandler.getBackupDataPKeys(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                hasDataOnly,
                                                minBackupLevel
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BACKUP_DATAS_PKEYS :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int batchSize=in.readCompressedInt();
                                            int[] pkeys=new int[batchSize];
                                            for(int c=0;c<batchSize;c++) pkeys[c]=in.readCompressedInt();
                                            if(batchSize==0) process.setCommand(
                                                "get_backup_datas_pkeys",
                                                Integer.toString(batchSize)
                                            ); else if(batchSize==1) process.setCommand(
                                                "get_backup_datas_pkeys",
                                                Integer.toString(batchSize),
                                                Integer.toString(pkeys[0])
                                            ); else process.setCommand(
                                                "get_backup_datas_pkeys",
                                                Integer.toString(batchSize),
                                                Integer.toString(pkeys[0]),
                                                "..."
                                            );
                                            BackupHandler.getBackupDatasPKeys(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                batchSize,
                                                pkeys
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BACKUP_PARTITION_DISK_TOTAL_SIZE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_BACKUP_PARTITION_TOTAL_SIZE,
                                                Integer.valueOf(pkey)
                                            );
                                            long size=BackupHandler.getBackupPartitionTotalSize(
                                                conn,
                                                backupConn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Long=size;
                                            hasResp2Long=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_BACKUP_PARTITION_DISK_USED_SIZE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_BACKUP_PARTITION_USED_SIZE,
                                                Integer.valueOf(pkey)
                                            );
                                            long size=BackupHandler.getBackupPartitionUsedSize(
                                                conn,
                                                backupConn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Long=size;
                                            hasResp2Long=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_CACHED_ROW_COUNT :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            process.setCommand(
                                                "get_cached_row_count",
                                                TableHandler.getTableName(
                                                    conn,
                                                    tableID
                                                )
                                            );
                                            int count=TableHandler.getCachedRowCount(
                                                conn,
                                                backupConn,
                                                source,
                                                tableID
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=count;
                                            hasResp2Int=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_CRON_TABLE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_CRON_TABLE,
                                                Integer.valueOf(pkey)
                                            );
                                            String cronTable=LinuxAccountHandler.getCronTable(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=cronTable;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_EMAIL_LIST_ADDRESS_LIST :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_EMAIL_LIST,
                                                Integer.valueOf(pkey)
                                            );
                                            String emailList=EmailHandler.getEmailListAddressList(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=emailList;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FAILOVER_FILE_LOGS_FOR_REPLICATION :
                                        {
                                            int replication = in.readCompressedInt();
                                            int maxRows = in.readCompressedInt();
                                            FailoverHandler.getFailoverFileLogs(
                                                conn,
                                                source,
                                                out,
                                                replication,
                                                maxRows
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FILE_BACKUPS_PKEYS :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int batchSize=in.readCompressedInt();
                                            int[] pkeys=new int[batchSize];
                                            for(int c=0;c<batchSize;c++) pkeys[c]=in.readCompressedInt();
                                            if(batchSize==0) process.setCommand(
                                                "get_file_backups_pkeys",
                                                Integer.toString(batchSize)
                                            ); else if(batchSize==1) process.setCommand(
                                                "get_file_backups_pkeys",
                                                Integer.toString(batchSize),
                                                Integer.toString(pkeys[0])
                                            ); else process.setCommand(
                                                "get_file_backups_pkeys",
                                                Integer.toString(batchSize),
                                                Integer.toString(pkeys[0]),
                                                "..."
                                            );
                                            BackupHandler.getFileBackupsPKeys(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                batchSize,
                                                pkeys
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FILE_BACKUPS_SERVER :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int server=in.readCompressedInt();
                                            process.setCommand(
                                                "get_file_backups_server",
                                                Integer.valueOf(server)
                                            );
                                            BackupHandler.getFileBackupsServer(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                server
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FILE_BACKUP_CHILDREN :
                                        {
                                            int server=in.readCompressedInt();
                                            String path=in.readUTF();
                                            process.setCommand(
                                                "get_file_backup_children",
                                                Integer.valueOf(server),
                                                path
                                            );
                                            BackupHandler.getFileBackupChildren(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                server,
                                                path
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FILE_BACKUP_SET_SERVER :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY-1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY-1);

                                            int server=in.readCompressedInt();
                                            String path=in.readBoolean()?in.readUTF():null;
                                            long time=in.readLong();
                                            process.setCommand(
                                                "get_file_backup_set_server",
                                                Integer.valueOf(server),
                                                path,
                                                time==-1?"''":SQLUtility.getDate(time)
                                            );
                                            BackupHandler.getFileBackupSetServer(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                server,
                                                path,
                                                time
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FILE_BACKUP_VERSIONS :
                                        {
                                            int server=in.readCompressedInt();
                                            String path=in.readUTF();
                                            process.setCommand(
                                                "get_file_backup_versions",
                                                Integer.valueOf(server),
                                                path
                                            );
                                            BackupHandler.getFileBackupVersions(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                server,
                                                path
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_FILENAME_FOR_BACKUP_DATA :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                "get_filename_for_backup_data",
                                                Integer.toString(pkey)
                                            );
                                            String filename=BackupHandler.getFilenameForBackupData(
                                                conn,
                                                backupConn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=filename==null?"":filename;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_IMAP_FOLDER_SIZES :
                                        {
                                            int pkey=in.readCompressedInt();
                                            int numFolders=in.readCompressedInt();
                                            String[] folderNames=new String[numFolders];
                                            for(int c=0;c<numFolders;c++) folderNames[c]=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.GET_IMAP_FOLDER_SIZES,
                                                Integer.valueOf(pkey),
                                                Integer.valueOf(numFolders),
                                                folderNames
                                            );
                                            resp2LongArray=EmailHandler.getImapFolderSizes(
                                                conn,
                                                source,
                                                pkey,
                                                folderNames
                                            );
                                            hasResp2LongArray=true;
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_INBOX_ATTRIBUTES :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_INBOX_ATTRIBUTES,
                                                Integer.valueOf(pkey)
                                            );
                                            resp2InboxAttributes=EmailHandler.getInboxAttributes(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            hasResp2InboxAttributes=true;
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_LATEST_FILE_BACKUP_SET :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            process.setCommand(
                                                "get_latest_file_backup_set",
                                                Integer.valueOf(server)
                                            );
                                            BackupHandler.getLatestFileBackupSet(
                                                conn,
                                                backupConn,
                                                source,
                                                out,
                                                server
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MAJORDOMO_INFO_FILE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_MAJORDOMO_INFO_FILE,
                                                Integer.valueOf(pkey)
                                            );
                                            String file=EmailHandler.getMajordomoInfoFile(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=file;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MAJORDOMO_INTRO_FILE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.GET_MAJORDOMO_INTRO_FILE,
                                                Integer.valueOf(pkey)
                                            );
                                            String file=EmailHandler.getMajordomoIntroFile(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=file;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MASTER_ENTROPY :
                                        {
                                            int numBytes=in.readCompressedInt();
                                            process.setCommand(
                                                "get_master_entropy",
                                                Integer.valueOf(numBytes)
                                            );
                                            byte[] bytes=RandomHandler.getMasterEntropy(conn, source, numBytes);
                                            out.writeByte(AOServProtocol.DONE);
                                            out.writeCompressedInt(bytes.length);
                                            for(int c=0;c<bytes.length;c++) out.writeByte(bytes[c]);
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MASTER_ENTROPY_NEEDED :
                                        {
                                            process.setCommand(
                                                "get_master_entropy_needed"
                                            );
                                            long needed=RandomHandler.getMasterEntropyNeeded(conn, source);
                                            resp1=AOServProtocol.DONE;
                                            resp2Long=needed;
                                            hasResp2Long=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MRTG_FILE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            String filename=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.GET_MRTG_FILE,
                                                Integer.valueOf(aoServer),
                                                filename
                                            );
                                            AOServerHandler.getMrtgFile(
                                                conn,
                                                source,
                                                aoServer,
                                                filename,
                                                out
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MYSQL_MASTER_STATUS :
                                        {
                                            int mysqlServer=in.readCompressedInt();
                                            process.setCommand(
                                                "get_mysql_master_status",
                                                Integer.valueOf(mysqlServer)
                                            );
                                            MySQLHandler.getMasterStatus(
                                                conn,
                                                source,
                                                mysqlServer,
                                                out
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_MYSQL_SLAVE_STATUS :
                                        {
                                            int failoverMySQLReplication=in.readCompressedInt();
                                            process.setCommand(
                                                "get_mysql_slave_status",
                                                Integer.valueOf(failoverMySQLReplication)
                                            );
                                            MySQLHandler.getSlaveStatus(
                                                conn,
                                                source,
                                                failoverMySQLReplication,
                                                out
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_OBJECT :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            process.setCommand(
                                                "get_object",
                                                TableHandler.getTableName(
                                                    conn,
                                                    tableID
                                                )
                                            );
                                            TableHandler.getObject(
                                                conn,
                                                backupConn,
                                                source,
                                                in,
                                                out,
                                                tableID
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_PENDING_PAYMENTS :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            process.setCommand(
                                                "get_pending_payments",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE
                                            );
                                            TransactionHandler.getPendingPayments(
                                                conn,
                                                source,
                                                out,
                                                provideProgress
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_ROOT_BUSINESS :
                                        {
                                            process.setCommand(AOSHCommand.GET_ROOT_BUSINESS);
                                            String bu=BusinessHandler.getRootBusiness();
                                            resp1=AOServProtocol.DONE;
                                            resp2String=bu;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_ROW_COUNT :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            process.setCommand(
                                                "get_row_count",
                                                TableHandler.getTableName(
                                                    conn,
                                                    tableID
                                                )
                                            );
                                            int count=TableHandler.getRowCount(
                                                conn,
                                                backupConn,
                                                source,
                                                tableID
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Int=count;
                                            hasResp2Int=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_SPAM_EMAIL_MESSAGES_FOR_EMAIL_SMTP_RELAY :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            int esr=in.readCompressedInt();
                                            process.setCommand(
                                                "get_spam_email_messages_for_email_smtp_relay",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                Integer.valueOf(esr)
                                            );
                                            EmailHandler.getSpamEmailMessagesForEmailSmtpRelay(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                esr
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TABLE :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) {
                                                writeObjects(source, out, provideProgress, new ArrayList<AOServObject>());                                                
                                            } else {
                                                if(
                                                    tableID==SchemaTable.TableID.BACKUP_DATA
                                                    || tableID==SchemaTable.TableID.DISTRO_FILES
                                                    || tableID==SchemaTable.TableID.FILE_BACKUPS
                                                    || tableID==SchemaTable.TableID.INTERBASE_BACKUPS
                                                    || tableID==SchemaTable.TableID.MYSQL_BACKUPS
                                                    || tableID==SchemaTable.TableID.POSTGRES_BACKUPS
                                                ) {
                                                    process.setPriority(Thread.NORM_PRIORITY-1);
                                                    currentThread.setPriority(Thread.NORM_PRIORITY-1);
                                                }

                                                process.setCommand(
                                                    AOSHCommand.SELECT,
                                                    "*",
                                                    "from",
                                                    TableHandler.getTableName(
                                                        conn,
                                                        tableID
                                                    )
                                                );
                                                TableHandler.getTable(
                                                    conn,
                                                    backupConn,
                                                    source,
                                                    in,
                                                    out,
                                                    provideProgress,
                                                    tableID
                                                );
                                            }
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TICKETS_BUSINESS_ADMINISTRATOR :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                "get_tickets_business_administrator",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                username
                                            );
                                            TicketHandler.getTicketsBusinessAdministrator(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TICKETS_CREATED_BUSINESS_ADMINISTRATOR :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                "get_tickets_created_business_administrator",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                username
                                            );
                                            TicketHandler.getTicketsCreatedBusinessAdministrator(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TICKETS_CLOSED_BUSINESS_ADMINISTRATOR :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                "get_tickets_closed_business_administrator",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                username
                                            );
                                            TicketHandler.getTicketsClosedBusinessAdministrator(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TICKETS_BUSINESS :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String packageName=in.readUTF().trim();
                                            process.setCommand(
                                                "get_tickets_business",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                packageName
                                            );
                                            TicketHandler.getTicketsBusiness(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                packageName
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TRANSACTIONS_BUSINESS :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String accounting=in.readUTF().trim();
                                            process.setCommand(
                                                "get_transactions_business",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                accounting
                                            );
                                            TransactionHandler.getTransactionsBusiness(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                accounting
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TRANSACTIONS_BUSINESS_ADMINISTRATOR :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                "get_transactions_business_administrator",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                username
                                            );
                                            TransactionHandler.getTransactionsBusinessAdministrator(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_TRANSACTIONS_SEARCH :
                                        {
                                            boolean provideProgress=in.readBoolean();
                                            TransactionSearchCriteria criteria=new TransactionSearchCriteria();
                                            criteria.read(in);
                                            process.setCommand(
                                                "get_transactions_search",
                                                provideProgress?Boolean.TRUE:Boolean.FALSE,
                                                "..."
                                            );
                                            TransactionHandler.getTransactionsSearch(
                                                conn,
                                                source,
                                                out,
                                                provideProgress,
                                                criteria
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.GET_WHOIS_HISTORY_WHOIS_OUTPUT :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                "get_whois_history_whois_output",
                                                Integer.valueOf(pkey)
                                            );
                                            String whoisOutput=DNSHandler.getWhoisHistoryOutput(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2String=whoisOutput;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.HOLD_TICKET :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.HOLD_TICKET,
                                                Integer.valueOf(ticketID),
                                                comments
                                            );
                                            TicketHandler.holdTicket(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    /*case AOServProtocol.INITIALIZE_HTTPD_SITE_PASSWD_FILE :
                                        {
                                            int sitePKey=in.readCompressedInt();
                                            String username=in.readUTF().trim();
                                            String encPassword=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.INITIALIZE_HTTPD_SITE_PASSWD_FILE,
                                                Integer.valueOf(sitePKey),
                                                username,
                                                encPassword
                                            );
                                            HttpdHandler.initializeHttpdSitePasswdFile(
                                                conn,
                                                source,
                                                sitePKey,
                                                username,
                                                encPassword
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;*/
                                    case AOServProtocol.IS_ACCOUNTING_AVAILABLE :
                                        {
                                            String accounting=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_ACCOUNTING_AVAILABLE,
                                                accounting
                                            );
                                            boolean isAvailable=BusinessHandler.isAccountingAvailable(
                                                conn,
                                                accounting
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_BUSINESS_ADMINISTRATOR_PASSWORD_SET :
                                        {
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_BUSINESS_ADMINISTRATOR_PASSWORD_SET,
                                                username
                                            );
                                            boolean isAvailable=BusinessHandler.isBusinessAdministratorPasswordSet(
                                                conn,
                                                source,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_DNS_ZONE_AVAILABLE :
                                        {
                                            String zone=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_DNS_ZONE_AVAILABLE,
                                                zone
                                            );
                                            boolean isAvailable=DNSHandler.isDNSZoneAvailable(
                                                conn,
                                                zone
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_EMAIL_DOMAIN_AVAILABLE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            String domain=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_EMAIL_DOMAIN_AVAILABLE,
                                                Integer.valueOf(aoServer),
                                                domain
                                            );
                                            boolean isAvailable=EmailHandler.isEmailDomainAvailable(
                                                conn,
                                                source,
                                                aoServer,
                                                domain
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_INTERBASE_DATABASE_NAME_AVAILABLE :
                                        {
                                            int dbGroup=in.readCompressedInt();
                                            String name=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_INTERBASE_DATABASE_NAME_AVAILABLE,
                                                Integer.valueOf(dbGroup),
                                                name
                                            );
                                            boolean isAvailable=InterBaseHandler.isInterBaseDatabaseNameAvailable(
                                                conn,
                                                source,
                                                dbGroup,
                                                name
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_INTERBASE_DB_GROUP_NAME_AVAILABLE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            String name=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_INTERBASE_DB_GROUP_NAME_AVAILABLE,
                                                Integer.valueOf(aoServer),
                                                name
                                            );
                                            boolean isAvailable=InterBaseHandler.isInterBaseDBGroupNameAvailable(
                                                conn,
                                                source,
                                                aoServer,
                                                name
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_INTERBASE_SERVER_USER_PASSWORD_SET :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_INTERBASE_SERVER_USER_PASSWORD_SET,
                                                Integer.valueOf(pkey)
                                            );
                                            boolean isAvailable=InterBaseHandler.isInterBaseServerUserPasswordSet(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_LINUX_GROUP_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_LINUX_GROUP_NAME_AVAILABLE,
                                                name
                                            );
                                            boolean isAvailable=LinuxAccountHandler.isLinuxGroupNameAvailable(
                                                conn,
                                                name
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_LINUX_SERVER_ACCOUNT_PASSWORD_SET :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_LINUX_SERVER_ACCOUNT_PASSWORD_SET,
                                                Integer.valueOf(pkey)
                                            );
                                            boolean isAvailable=LinuxAccountHandler.isLinuxServerAccountPasswordSet(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_LINUX_SERVER_ACCOUNT_PROCMAIL_MANUAL :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_LINUX_SERVER_ACCOUNT_PROCMAIL_MANUAL,
                                                Integer.valueOf(pkey)
                                            );
                                            int isManual=LinuxAccountHandler.isLinuxServerAccountProcmailManual(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_6)>=0) {
                                                resp2Int=isManual;
                                                hasResp2Int=true;
                                            } else {
                                                if(isManual==AOServProtocol.FALSE) resp2Boolean=false;
                                                else if(isManual==AOServProtocol.TRUE) resp2Boolean=true;
                                                else throw new IOException("Unsupported value for AOServClient protocol < "+AOServProtocol.VERSION_1_6);
                                                hasResp2Boolean=true;
                                            }
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_MYSQL_DATABASE_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            int mysqlServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_MYSQL_DATABASE_NAME_AVAILABLE,
                                                name,
                                                Integer.valueOf(mysqlServer)
                                            );
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_4)<0) throw new IOException(AOSHCommand.IS_MYSQL_DATABASE_NAME_AVAILABLE+" call not supported for AOServProtocol < "+AOServProtocol.VERSION_1_4+", please upgrade AOServ Client.");
                                            boolean isAvailable=MySQLHandler.isMySQLDatabaseNameAvailable(
                                                conn,
                                                source,
                                                name,
                                                mysqlServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_MYSQL_SERVER_USER_PASSWORD_SET :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_MYSQL_SERVER_USER_PASSWORD_SET,
                                                Integer.valueOf(pkey)
                                            );
                                            boolean isAvailable=MySQLHandler.isMySQLServerUserPasswordSet(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_PACKAGE_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_PACKAGE_NAME_AVAILABLE,
                                                name
                                            );
                                            boolean isAvailable=PackageHandler.isPackageNameAvailable(
                                                conn,
                                                name
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_POSTGRES_DATABASE_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            int postgresServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_POSTGRES_DATABASE_NAME_AVAILABLE,
                                                name,
                                                Integer.valueOf(postgresServer)
                                            );
                                            boolean isAvailable=PostgresHandler.isPostgresDatabaseNameAvailable(
                                                conn,
                                                source,
                                                name,
                                                postgresServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_POSTGRES_SERVER_USER_PASSWORD_SET :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_POSTGRES_SERVER_USER_PASSWORD_SET,
                                                Integer.valueOf(pkey)
                                            );
                                            boolean isAvailable=PostgresHandler.isPostgresServerUserPasswordSet(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_POSTGRES_SERVER_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.IS_POSTGRES_SERVER_NAME_AVAILABLE,
                                                name,
                                                Integer.valueOf(aoServer)
                                            );
                                            boolean isAvailable=PostgresHandler.isPostgresServerNameAvailable(
                                                conn,
                                                source,
                                                name,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_SHARED_TOMCAT_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_SHARED_TOMCAT_NAME_AVAILABLE,
                                                name
                                            );
                                            boolean isAvailable=HttpdHandler.isSharedTomcatNameAvailable(
                                                conn,
                                                name
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_USERNAME_AVAILABLE :
                                        {
                                            String username=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_USERNAME_AVAILABLE,
                                                username
                                            );
                                            boolean isAvailable=UsernameHandler.isUsernameAvailable(
                                                conn,
                                                username
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.IS_SITE_NAME_AVAILABLE :
                                        {
                                            String name=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.IS_SITE_NAME_AVAILABLE,
                                                name
                                            );
                                            boolean isAvailable=HttpdHandler.isSiteNameAvailable(
                                                conn,
                                                name
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=isAvailable;
                                            hasResp2Boolean=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.KILL_TICKET :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.KILL_TICKET,
                                                username,
                                                comments
                                            );
                                            TicketHandler.killTicket(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.MOVE_IP_ADDRESS :
                                        {
                                            int ipAddress=in.readCompressedInt();
                                            int toServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.MOVE_IP_ADDRESS,
                                                Integer.valueOf(ipAddress),
                                                Integer.valueOf(toServer)
                                            );
                                            IPAddressHandler.moveIPAddress(
                                                conn,
                                                source,
                                                invalidateList,
                                                ipAddress,
                                                toServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REACTIVATE_TICKET :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.REACTIVATE_TICKET,
                                                Integer.valueOf(ticketID),
                                                username,
                                                comments
                                            );
                                            TicketHandler.reactivateTicket(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REFRESH_EMAIL_SMTP_RELAY :
                                        {
                                            process.setPriority(Thread.NORM_PRIORITY+1);
                                            currentThread.setPriority(Thread.NORM_PRIORITY+1);

                                            int pkey=in.readCompressedInt();
                                            long min_duration=in.readLong();
                                            process.setCommand(
                                                AOSHCommand.REFRESH_EMAIL_SMTP_RELAY,
                                                Integer.valueOf(pkey),
                                                Long.valueOf(min_duration)
                                            );
                                            EmailHandler.refreshEmailSmtpRelay(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                min_duration
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REMOVE : {
                                        int clientTableID=in.readCompressedInt();
                                        SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                        if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                        switch(tableID) {
                                            case BLACKHOLE_EMAIL_ADDRESSES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_BLACKHOLE_EMAIL_ADDRESS,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeBlackholeEmailAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case BUSINESS_ADMINISTRATORS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_BUSINESS_ADMINISTRATOR,
                                                        username
                                                    );
                                                    BusinessHandler.removeBusinessAdministrator(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case BUSINESS_SERVERS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_BUSINESS_SERVER,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    BusinessHandler.removeBusinessServer(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case CREDIT_CARDS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_CREDIT_CARD,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    CreditCardHandler.removeCreditCard(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case CVS_REPOSITORIES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_CVS_REPOSITORY,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    CvsHandler.removeCvsRepository(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case DNS_RECORDS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_DNS_RECORD,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    DNSHandler.removeDNSRecord(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case DNS_ZONES :
                                                {
                                                    String zone=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_DNS_ZONE,
                                                        zone
                                                    );
                                                    DNSHandler.removeDNSZone(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        zone
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_ADDRESSES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_ADDRESS,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_DOMAINS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_DOMAIN,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailDomain(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_FORWARDING :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_FORWARDING,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailForwarding(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_LIST_ADDRESSES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_LIST_ADDRESS,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailListAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_LISTS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_LIST,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailList(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_PIPE_ADDRESSES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_PIPE_ADDRESS,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailPipeAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_PIPES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_PIPE,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailPipe(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case EMAIL_SMTP_RELAYS :
                                                {
                                                    process.setPriority(Thread.NORM_PRIORITY+1);
                                                    currentThread.setPriority(Thread.NORM_PRIORITY+1);

                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_EMAIL_SMTP_RELAY,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeEmailSmtpRelay(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case FILE_BACKUP_SETTINGS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_FILE_BACKUP_SETTING,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    BackupHandler.removeFileBackupSetting(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case FILE_BACKUPS :
                                                {
                                                    process.setPriority(Thread.MIN_PRIORITY+1);
                                                    currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_FILE_BACKUP,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    BackupHandler.removeFileBackup(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case FTP_GUEST_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_FTP_GUEST_USER,
                                                        username
                                                    );
                                                    FTPHandler.removeFTPGuestUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_SHARED_TOMCATS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_HTTPD_SHARED_TOMCAT,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdSharedTomcat(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_SITE_AUTHENTICATED_LOCATIONS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        "remove_httpd_site_authenticated_location",
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdSiteAuthenticatedLocation(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_SITES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_HTTPD_SITE,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdSite(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_SITE_URLS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_HTTPD_SITE_URL,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdSiteURL(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_TOMCAT_CONTEXTS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_HTTPD_TOMCAT_CONTEXT,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdTomcatContext(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_TOMCAT_DATA_SOURCES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_HTTPD_TOMCAT_DATA_SOURCE,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdTomcatDataSource(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case HTTPD_TOMCAT_PARAMETERS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_HTTPD_TOMCAT_PARAMETER,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    HttpdHandler.removeHttpdTomcatParameter(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INCOMING_PAYMENTS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_INCOMING_PAYMENT,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    TransactionHandler.removeIncomingPayment(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INTERBASE_BACKUPS :
                                                {
                                                    process.setPriority(Thread.MIN_PRIORITY+1);
                                                    currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_INTERBASE_BACKUP,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    InterBaseHandler.removeInterBaseBackup(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INTERBASE_DATABASES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_INTERBASE_DATABASE,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    InterBaseHandler.removeInterBaseDatabase(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INTERBASE_DB_GROUPS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_INTERBASE_DB_GROUP,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    InterBaseHandler.removeInterBaseDBGroup(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INTERBASE_SERVER_USERS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_INTERBASE_SERVER_USER,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    InterBaseHandler.removeInterBaseServerUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case INTERBASE_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_INTERBASE_USER,
                                                        username
                                                    );
                                                    InterBaseHandler.removeInterBaseUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_ACC_ADDRESSES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_LINUX_ACC_ADDRESS,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    EmailHandler.removeLinuxAccAddress(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_ACCOUNTS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_LINUX_ACCOUNT,
                                                        username
                                                    );
                                                    LinuxAccountHandler.removeLinuxAccount(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_GROUP_ACCOUNTS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_LINUX_GROUP_ACCOUNT,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    LinuxAccountHandler.removeLinuxGroupAccount(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_GROUPS :
                                                {
                                                    String name=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_LINUX_GROUP,
                                                        name
                                                    );
                                                    LinuxAccountHandler.removeLinuxGroup(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        name
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_SERVER_ACCOUNTS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_LINUX_SERVER_ACCOUNT,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    LinuxAccountHandler.removeLinuxServerAccount(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case LINUX_SERVER_GROUPS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_LINUX_SERVER_GROUP,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    LinuxAccountHandler.removeLinuxServerGroup(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MAJORDOMO_SERVERS :
                                                {
                                                    int domain=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_MAJORDOMO_SERVER,
                                                        Integer.valueOf(domain)
                                                    );
                                                    EmailHandler.removeMajordomoServer(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        domain
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MYSQL_BACKUPS :
                                                {
                                                    process.setPriority(Thread.MIN_PRIORITY+1);
                                                    currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_MYSQL_BACKUP,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    MySQLHandler.removeMySQLBackup(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MYSQL_DATABASES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_MYSQL_DATABASE,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    MySQLHandler.removeMySQLDatabase(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MYSQL_DB_USERS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_MYSQL_DB_USER,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    MySQLHandler.removeMySQLDBUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MYSQL_SERVER_USERS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_MYSQL_SERVER_USER,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    MySQLHandler.removeMySQLServerUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case MYSQL_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_MYSQL_USER,
                                                        username
                                                    );
                                                    MySQLHandler.removeMySQLUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case NET_BINDS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_NET_BIND,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    NetBindHandler.removeNetBind(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case PACKAGE_DEFINITIONS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        "remove_package_definition",
                                                        Integer.valueOf(pkey)
                                                    );
                                                    PackageHandler.removePackageDefinition(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case POSTGRES_BACKUPS :
                                                {
                                                    process.setPriority(Thread.MIN_PRIORITY+1);
                                                    currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_POSTGRES_BACKUP,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    PostgresHandler.removePostgresBackup(
                                                        conn,
                                                        backupConn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case POSTGRES_DATABASES :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_POSTGRES_DATABASE,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    PostgresHandler.removePostgresDatabase(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case POSTGRES_SERVER_USERS :
                                                {
                                                    int pkey=in.readCompressedInt();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_POSTGRES_SERVER_USER,
                                                        Integer.valueOf(pkey)
                                                    );
                                                    PostgresHandler.removePostgresServerUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        pkey
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case POSTGRES_USERS :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_POSTGRES_USER,
                                                        username
                                                    );
                                                    PostgresHandler.removePostgresUser(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            case USERNAMES :
                                                {
                                                    String username=in.readUTF().trim();
                                                    process.setCommand(
                                                        AOSHCommand.REMOVE_USERNAME,
                                                        username
                                                    );
                                                    UsernameHandler.removeUsername(
                                                        conn,
                                                        source,
                                                        invalidateList,
                                                        username
                                                    );
                                                    resp1=AOServProtocol.DONE;
                                                }
                                                break;
                                            default :
                                                throw new IOException("Unknown table ID for remove: clientTableID="+clientTableID+", tableID="+tableID);
                                        }
                                        sendInvalidateList=true;
                                        break;
                                    }
                                    case AOServProtocol.REMOVE_EXPIRED_FILE_BACKUPS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            process.setCommand(
                                                "remove_expired_file_backups",
                                                Integer.valueOf(server)
                                            );
                                            BackupHandler.removeExpiredFileBackups(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                server
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REMOVE_EXPIRED_INTERBASE_BACKUPS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                "remove_expired_interbase_backups",
                                                Integer.valueOf(aoServer)
                                            );
                                            InterBaseHandler.removeExpiredInterBaseBackups(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REMOVE_EXPIRED_MYSQL_BACKUPS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                "remove_expired_mysql_backups",
                                                Integer.valueOf(aoServer)
                                            );
                                            MySQLHandler.removeExpiredMySQLBackups(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REMOVE_EXPIRED_POSTGRES_BACKUPS :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                "remove_expired_postgres_backups",
                                                Integer.valueOf(aoServer)
                                            );
                                            PostgresHandler.removeExpiredPostgresBackups(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REMOVE_UNUSED_BACKUP_DATAS :
                                        {
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_109)>=0) {
                                                throw new IOException("Clients version "+AOServProtocol.VERSION_1_0_A_109+" and newer should not be making this call to REMOVE_UNUSED_BACKUP_DATAS.");
                                            }

                                            // Silently ignore for older clients
                                            int batchSize=in.readCompressedInt();
                                            int[] pkeys=new int[batchSize];
                                            for(int c=0;c<batchSize;c++) pkeys[c]=in.readCompressedInt();
                                            if(batchSize==1) {
                                                process.setCommand(
                                                    "remove_unused_backup_datas",
                                                    Integer.valueOf(batchSize),
                                                    Integer.valueOf(pkeys[0])
                                                );
                                            } else {
                                                process.setCommand(
                                                    "remove_unused_backup_datas",
                                                    Integer.valueOf(batchSize),
                                                    Integer.valueOf(pkeys[0]),
                                                    "..."
                                                );
                                            }
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.REQUEST_DAEMON_ACCESS :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            int daemonCommandCode=in.readCompressedInt();
                                            int param1=in.readCompressedInt();
                                            process.setCommand(
                                                "request_daemon_access",
                                                Integer.valueOf(aoServer),
                                                Integer.valueOf(daemonCommandCode),
                                                Integer.valueOf(param1)
                                            );
                                            long key=DaemonHandler.requestDaemonAccess(
                                                conn,
                                                source,
                                                aoServer,
                                                daemonCommandCode,
                                                param1
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Long=key;
                                            hasResp2Long=true;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.REQUEST_SEND_BACKUP_DATA_TO_DAEMON :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int backupData = in.readCompressedInt();
                                            long md5_hi = in.readLong();
                                            long md5_lo = in.readLong();
                                            process.setCommand(
                                                "request_send_backup_data_to_daemon",
                                                Integer.valueOf(backupData),
                                                Long.valueOf(md5_hi),
                                                Long.valueOf(md5_lo)
                                            );
                                            BackupHandler.requestSendBackupDataToDaemon(
                                                conn,
                                                backupConn,
                                                source,
                                                backupData,
                                                md5_hi,
                                                md5_lo,
                                                out
                                            );
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_APACHE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.RESTART_APACHE,
                                                Integer.valueOf(aoServer)
                                            );
                                            HttpdHandler.restartApache(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_CRON :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.RESTART_CRON,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.restartCron(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_INTERBASE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.RESTART_INTERBASE,
                                                Integer.valueOf(aoServer)
                                            );
                                            InterBaseHandler.restartInterBase(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_MYSQL :
                                        {
                                            int mysqlServer=in.readCompressedInt();
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_4)<0) throw new IOException("addBackupServer call not supported for AOServ Client version < "+AOServProtocol.VERSION_1_4+", please upgrade AOServ Client.");
                                            process.setCommand(
                                                AOSHCommand.RESTART_MYSQL,
                                                Integer.valueOf(mysqlServer)
                                            );
                                            MySQLHandler.restartMySQL(
                                                conn,
                                                source,
                                                mysqlServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_POSTGRESQL :
                                        {
                                            int postgresServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.RESTART_POSTGRESQL,
                                                Integer.valueOf(postgresServer)
                                            );
                                            PostgresHandler.restartPostgreSQL(
                                                conn,
                                                source,
                                                postgresServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_XFS :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.RESTART_XFS,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.restartXfs(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.RESTART_XVFB :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.RESTART_XVFB,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.restartXvfb(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SEND_BACKUP_DATA :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int backupData=in.readCompressedInt();
                                            String filename=in.readUTF();
                                            boolean isCompressed=in.readBoolean();
                                            long md5_hi=in.readLong();
                                            long md5_lo=in.readLong();
                                            process.setCommand(
                                                "send_backup_data",
                                                Integer.valueOf(backupData),
                                                filename,
                                                isCompressed?Boolean.TRUE:Boolean.FALSE,
                                                Long.valueOf(md5_hi),
                                                Long.valueOf(md5_lo)
                                            );
                                            BackupHandler.sendBackupData(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                in,
                                                backupData,
                                                filename,
                                                isCompressed,
                                                md5_hi,
                                                md5_lo
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_AUTORESPONDER :
                                        {
                                            int pkey=in.readCompressedInt();
                                            int from=in.readCompressedInt();
                                            String subject=in.readBoolean()?in.readUTF().trim():null;
                                            String content=in.readBoolean()?in.readUTF():null;
                                            boolean enabled=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_AUTORESPONDER,
                                                Integer.valueOf(pkey),
                                                from==-1?null:Integer.valueOf(from),
                                                subject,
                                                content,
                                                enabled?Boolean.TRUE:Boolean.FALSE
                                            );
                                            LinuxAccountHandler.setAutoresponder(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                from,
                                                subject,
                                                content,
                                                enabled
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_BACKUP_RETENTION :
                                        {
                                            short days=in.readShort();
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            switch(tableID) {
                                                case CVS_REPOSITORIES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.SET_CVS_REPOSITORY_BACKUP_RETENTION,
                                                            Integer.valueOf(pkey),
                                                            Short.valueOf(days)
                                                        );
                                                        CvsHandler.setBackupRetention(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey,
                                                            days
                                                        );
                                                    }
                                                    break;
                                                case EMAIL_LISTS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.SET_EMAIL_LIST_BACKUP_RETENTION,
                                                            Integer.valueOf(pkey),
                                                            Short.valueOf(days)
                                                        );
                                                        EmailHandler.setEmailListBackupRetention(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey,
                                                            days
                                                        );
                                                    }
                                                    break;
                                                case HTTPD_SHARED_TOMCATS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        int clientColumnID=in.readCompressedInt();
                                                        if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SHARED_TOMCATS, "config_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SHARED_TOMCAT_CONFIG_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSharedTomcatConfigBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SHARED_TOMCATS, "file_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SHARED_TOMCAT_FILE_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSharedTomcatFileBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SHARED_TOMCATS, "log_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SHARED_TOMCAT_LOG_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSharedTomcatLogBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else throw new IOException("Unknown client column ID for set_httpd_shared_tomcat_backup_retention: " + clientColumnID);
                                                    }
                                                    break;
                                                case HTTPD_SITES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        int clientColumnID=in.readCompressedInt();
                                                        if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SITES, "config_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SITE_CONFIG_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSiteConfigBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SITES, "file_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SITE_FILE_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSiteFileBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SITES, "ftp_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SITE_FTP_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSiteFtpBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.HTTPD_SITES, "log_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_HTTPD_SITE_LOG_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            HttpdHandler.setHttpdSiteLogBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else throw new IOException("Unknown column ID for set_httpd_site_backup_retention: " + clientColumnID);
                                                    }
                                                    break;
                                                case LINUX_SERVER_ACCOUNTS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        int clientColumnID=in.readCompressedInt();
                                                        if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.LINUX_SERVER_ACCOUNTS, "cron_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_CRON_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            LinuxAccountHandler.setLinuxServerAccountCronBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.LINUX_SERVER_ACCOUNTS, "home_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_HOME_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            LinuxAccountHandler.setLinuxServerAccountHomeBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else if(clientColumnID==TableHandler.getClientColumnIndex(conn, source, SchemaTable.TableID.LINUX_SERVER_ACCOUNTS, "inbox_backup_retention")) {
                                                            process.setCommand(
                                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_INBOX_BACKUP_RETENTION,
                                                                Integer.valueOf(pkey),
                                                                Short.valueOf(days)
                                                            );
                                                            LinuxAccountHandler.setLinuxServerAccountInboxBackupRetention(
                                                                conn,
                                                                source,
                                                                invalidateList,
                                                                pkey,
                                                                days
                                                            );
                                                        } else throw new IOException("Unknown column ID for set_linux_server_account_backup_retention: " + clientColumnID);
                                                    }
                                                    break;
                                                case MAJORDOMO_SERVERS :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.SET_MAJORDOMO_SERVER_BACKUP_RETENTION,
                                                            Integer.valueOf(pkey),
                                                            Short.valueOf(days)
                                                        );
                                                        EmailHandler.setMajordomoServerBackupRetention(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey,
                                                            days
                                                        );
                                                    }
                                                    break;
                                                case MYSQL_DATABASES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.SET_MYSQL_DATABASE_BACKUP_RETENTION,
                                                            Integer.valueOf(pkey),
                                                            Short.valueOf(days)
                                                        );
                                                        MySQLHandler.setMySQLDatabaseBackupRetention(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey,
                                                            days
                                                        );
                                                    }
                                                    break;
                                                case POSTGRES_DATABASES :
                                                    {
                                                        int pkey=in.readCompressedInt();
                                                        process.setCommand(
                                                            AOSHCommand.SET_POSTGRES_DATABASE_BACKUP_RETENTION,
                                                            Integer.valueOf(pkey),
                                                            Short.valueOf(days)
                                                        );
                                                        PostgresHandler.setPostgresDatabaseBackupRetention(
                                                            conn,
                                                            source,
                                                            invalidateList,
                                                            pkey,
                                                            days
                                                        );
                                                    }
                                                    break;
                                                default :
                                                    throw new IOException("Unknown table ID for set_backup_retention: clientTableID="+clientTableID+", tableID="+tableID);
                                            }
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_BUSINESS_ACCOUNTING :
                                        {
                                            String oldAccounting=in.readUTF().trim();
                                            String newAccounting=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_BUSINESS_ACCOUNTING,
                                                oldAccounting,
                                                newAccounting
                                            );
                                            BusinessHandler.setBusinessAccounting(
                                                conn,
                                                source,
                                                invalidateList,
                                                oldAccounting,
                                                newAccounting
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_BUSINESS_ADMINISTRATOR_PASSWORD :
                                        {
                                            String username=in.readUTF().trim();
                                            String password=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_BUSINESS_ADMINISTRATOR_PASSWORD,
                                                username,
                                                AOServProtocol.FILTERED
                                            );
                                            BusinessHandler.setBusinessAdministratorPassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_BUSINESS_ADMINISTRATOR_PROFILE :
                                        {
                                            String username=in.readUTF().trim();
                                            String name=in.readUTF().trim();
                                            String title=in.readBoolean()?in.readUTF().trim():null;
                                            long birthday=in.readLong();
                                            boolean isPrivate=in.readBoolean();
                                            String workPhone=in.readUTF().trim();
                                            String homePhone=in.readBoolean()?in.readUTF().trim():null;
                                            String cellPhone=in.readBoolean()?in.readUTF().trim():null;
                                            String fax=in.readBoolean()?in.readUTF().trim():null;
                                            String email=in.readUTF().trim();
                                            String address1=in.readBoolean()?in.readUTF().trim():null;
                                            String address2=in.readBoolean()?in.readUTF().trim():null;
                                            String city=in.readBoolean()?in.readUTF().trim():null;
                                            String state=in.readBoolean()?in.readUTF().trim():null;
                                            String country=in.readBoolean()?in.readUTF().trim():null;
                                            String zip=in.readBoolean()?in.readUTF().trim():null;
                                            process.setCommand(
                                                AOSHCommand.SET_BUSINESS_ADMINISTRATOR_PROFILE,
                                                username,
                                                name,
                                                title,
                                                birthday==BusinessAdministrator.NONE?null:new java.util.Date(birthday),
                                                isPrivate?Boolean.TRUE:Boolean.FALSE,
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
                                                zip
                                            );
                                            BusinessHandler.setBusinessAdministratorProfile(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                name,
                                                title,
                                                birthday,
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
                                                zip
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_CRON_TABLE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String crontab=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_CRON_TABLE,
                                                Integer.valueOf(pkey),
                                                crontab
                                            );
                                            LinuxAccountHandler.setCronTable(
                                                conn,
                                                source,
                                                pkey,
                                                crontab
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_CVS_REPOSITORY_MODE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            long mode=in.readLong();
                                            process.setCommand(
                                                AOSHCommand.SET_CVS_REPOSITORY_MODE,
                                                Integer.valueOf(pkey),
                                                Long.toOctalString(mode)
                                            );
                                            CvsHandler.setMode(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                mode
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_DEFAULT_BUSINESS_SERVER :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.SET_DEFAULT_BUSINESS_SERVER,
                                                Integer.valueOf(pkey)
                                            );
                                            BusinessHandler.setDefaultBusinessServer(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_DNS_ZONE_TTL :
                                        {
                                            String zone=in.readUTF();
                                            int ttl=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.SET_DNS_ZONE_TTL,
                                                zone,
                                                Integer.valueOf(ttl)
                                            );
                                            DNSHandler.setDNSZoneTTL(
                                                conn,
                                                source,
                                                invalidateList,
                                                zone,
                                                ttl
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_EMAIL_LIST_ADDRESS_LIST :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String list=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_EMAIL_LIST,
                                                Integer.valueOf(pkey),
                                                list
                                            );
                                            EmailHandler.setEmailListAddressList(
                                                conn,
                                                source,
                                                pkey,
                                                list
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_FILE_BACKUP_SETTINGS :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String path=in.readUTF();
                                            int packageNum=in.readCompressedInt();
                                            short backupLevel=in.readShort();
                                            short backupRetention=in.readShort();
                                            boolean recurse=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_FILE_BACKUP_SETTING,
                                                Integer.valueOf(pkey),
                                                path,
                                                Integer.valueOf(packageNum),
                                                Short.valueOf(backupLevel),
                                                Short.valueOf(backupRetention),
                                                recurse?Boolean.TRUE:Boolean.FALSE
                                            );
                                            BackupHandler.setFileBackupSettings(
                                                conn,
                                                backupConn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                path,
                                                packageNum,
                                                backupLevel,
                                                backupRetention,
                                                recurse
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean is_manual=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_HTTPD_SHARED_TOMCAT_IS_MANUAL,
                                                Integer.valueOf(pkey),
                                                is_manual?Boolean.TRUE:Boolean.FALSE
                                            );
                                            HttpdHandler.setHttpdSharedTomcatIsManual(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                is_manual
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SITE_AUTHENTICATED_LOCATION_ATTRIBUTES :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String path=in.readUTF().trim();
                                            boolean isRegularExpression=in.readBoolean();
                                            String authName=in.readUTF().trim();
                                            String authGroupFile=in.readUTF().trim();
                                            String authUserFile=in.readUTF().trim();
                                            String require=in.readUTF().trim();
                                            process.setCommand(
                                                "set_httpd_site_authenticated_location_attributes",
                                                Integer.valueOf(pkey),
                                                path,
                                                isRegularExpression?Boolean.TRUE:Boolean.FALSE,
                                                authName,
                                                authGroupFile,
                                                authUserFile,
                                                require
                                            );
                                            HttpdHandler.setHttpdSiteAuthenticatedLocationAttributes(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                path,
                                                isRegularExpression,
                                                authName,
                                                authGroupFile,
                                                authUserFile,
                                                require
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SITE_BIND_IS_MANUAL :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean is_manual=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_HTTPD_SITE_BIND_IS_MANUAL,
                                                Integer.valueOf(pkey),
                                                is_manual?Boolean.TRUE:Boolean.FALSE
                                            );
                                            HttpdHandler.setHttpdSiteBindIsManual(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                is_manual
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SITE_BIND_REDIRECT_TO_PRIMARY_HOSTNAME :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean redirect_to_primary_hostname=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_HTTPD_SITE_BIND_REDIRECT_TO_PRIMARY_HOSTNAME,
                                                Integer.valueOf(pkey),
                                                redirect_to_primary_hostname?Boolean.TRUE:Boolean.FALSE
                                            );
                                            HttpdHandler.setHttpdSiteBindRedirectToPrimaryHostname(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                redirect_to_primary_hostname
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SITE_IS_MANUAL :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean is_manual=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_HTTPD_SITE_IS_MANUAL,
                                                Integer.valueOf(pkey),
                                                is_manual?Boolean.TRUE:Boolean.FALSE
                                            );
                                            HttpdHandler.setHttpdSiteIsManual(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                is_manual
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SITE_SERVER_ADMIN :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String emailAddress=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_HTTPD_SITE_SERVER_ADMIN,
                                                Integer.valueOf(pkey),
                                                emailAddress
                                            );
                                            HttpdHandler.setHttpdSiteServerAdmin(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                emailAddress
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_SITE_BIND_PREDISABLE_CONFIG :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String config=in.readBoolean()?in.readUTF().trim():null;
                                            process.setCommand(
                                                "set_httpd_site_bind_predisable_config",
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            HttpdHandler.setHttpdSiteBindPredisableConfig(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                config
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_HTTPD_TOMCAT_CONTEXT_ATTRIBUTES :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String className=in.readBoolean()?in.readUTF().trim():null;
                                            boolean cookies=in.readBoolean();
                                            boolean crossContext=in.readBoolean();
                                            String docBase=in.readUTF().trim();
                                            boolean override=in.readBoolean();
                                            String path=in.readUTF().trim();
                                            boolean privileged=in.readBoolean();
                                            boolean reloadable=in.readBoolean();
                                            boolean useNaming=in.readBoolean();
                                            String wrapperClass=in.readBoolean()?in.readUTF().trim():null;
                                            int debug=in.readCompressedInt();
                                            String workDir=in.readBoolean()?in.readUTF().trim():null;
                                            process.setCommand(
                                                AOSHCommand.SET_HTTPD_TOMCAT_CONTEXT_ATTRIBUTES,
                                                Integer.valueOf(pkey),
                                                className,
                                                cookies?Boolean.TRUE:Boolean.FALSE,
                                                crossContext?Boolean.TRUE:Boolean.FALSE,
                                                docBase,
                                                override?Boolean.TRUE:Boolean.FALSE,
                                                path,
                                                privileged?Boolean.TRUE:Boolean.FALSE,
                                                reloadable?Boolean.TRUE:Boolean.FALSE,
                                                useNaming?Boolean.TRUE:Boolean.FALSE,
                                                wrapperClass,
                                                Integer.valueOf(debug),
                                                workDir
                                            );
                                            HttpdHandler.setHttpdTomcatContextAttributes(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                className,
                                                cookies,
                                                crossContext,
                                                docBase,
                                                override,
                                                path,
                                                privileged,
                                                reloadable,
                                                useNaming,
                                                wrapperClass,
                                                debug,
                                                workDir
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_IMAP_FOLDER_SUBSCRIBED :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String folderName=in.readUTF();
                                            boolean subscribed=in.readBoolean();
                                            process.setCommand(
                                                "set_imap_folder_subscribed",
                                                Integer.valueOf(pkey),
                                                folderName,
                                                subscribed?"true":"false"
                                            );
                                            EmailHandler.setImapFolderSubscribed(
                                                conn,
                                                source,
                                                pkey,
                                                folderName,
                                                subscribed
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_INTERBASE_SERVER_USER_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                AOSHCommand.SET_INTERBASE_SERVER_USER_PASSWORD,
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            InterBaseHandler.setInterBaseServerUserPassword(
                                                conn,
                                                source,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_INTERBASE_SERVER_USER_PREDISABLE_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                "set_interbase_server_user_predisable_password",
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            InterBaseHandler.setInterBaseServerUserPredisablePassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_IP_ADDRESS_DHCP_ADDRESS :
                                        {
                                            int ipAddress=in.readCompressedInt();
                                            String dhcpAddress=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_IP_ADDRESS_DHCP_ADDRESS,
                                                Integer.valueOf(ipAddress),
                                                dhcpAddress
                                            );
                                            IPAddressHandler.setIPAddressDHCPAddress(
                                                conn,
                                                source,
                                                invalidateList,
                                                ipAddress,
                                                dhcpAddress
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_IP_ADDRESS_HOSTNAME :
                                        {
                                            int ipAddress=in.readCompressedInt();
                                            String hostname=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_IP_ADDRESS_HOSTNAME,
                                                Integer.valueOf(ipAddress),
                                                hostname
                                            );
                                            IPAddressHandler.setIPAddressHostname(
                                                conn,
                                                source,
                                                invalidateList,
                                                ipAddress,
                                                hostname
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_IP_ADDRESS_PACKAGE :
                                        {
                                            int ipAddress=in.readCompressedInt();
                                            String packageName=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_IP_ADDRESS_PACKAGE,
                                                Integer.valueOf(ipAddress),
                                                packageName
                                            );
                                            IPAddressHandler.setIPAddressPackage(
                                                conn,
                                                source,
                                                invalidateList,
                                                ipAddress,
                                                packageName
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LAST_BACKUP_TIME :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int server=in.readCompressedInt();
                                            long time=in.readLong();
                                            process.setCommand(
                                                "set_last_backup_time",
                                                Integer.valueOf(server),
                                                new java.util.Date(time)
                                            );
                                            BackupHandler.setLastBackupTime(
                                                conn,
                                                source,
                                                invalidateList,
                                                server,
                                                time
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LAST_DISTRO_TIME :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int ao_server=in.readCompressedInt();
                                            long time=in.readLong();
                                            process.setCommand(
                                                "set_last_distro_time",
                                                Integer.valueOf(ao_server),
                                                new java.util.Date(time)
                                            );
                                            AOServerHandler.setLastDistroTime(
                                                conn,
                                                source,
                                                invalidateList,
                                                ao_server,
                                                time
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LAST_FAILOVER_REPLICATION_TIME :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int ffr=in.readCompressedInt();
                                            long time=in.readLong();
                                            process.setCommand(
                                                "set_last_failover_replication_time",
                                                Integer.valueOf(ffr),
                                                new java.util.Date(time)
                                            );
                                            FailoverHandler.setLastFailoverReplicationTime(
                                                conn,
                                                source,
                                                invalidateList,
                                                ffr,
                                                time
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_ACCOUNT_HOME_PHONE :
                                        {
                                            String username=in.readUTF().trim();
                                            String phone=in.readUTF().trim();
                                            if(phone.length()==0) phone=null;
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_ACCOUNT_HOME_PHONE,
                                                username,
                                                phone
                                            );
                                            LinuxAccountHandler.setLinuxAccountHomePhone(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                phone
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_ACCOUNT_NAME :
                                        {
                                            String username=in.readUTF().trim();
                                            String fullName=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_ACCOUNT_NAME,
                                                username,
                                                fullName
                                            );
                                            LinuxAccountHandler.setLinuxAccountName(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                fullName
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_ACCOUNT_OFFICE_LOCATION :
                                        {
                                            String username=in.readUTF().trim();
                                            String location=in.readUTF().trim();
                                            if(location.length()==0) location=null;
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_ACCOUNT_OFFICE_LOCATION,
                                                username,
                                                location
                                            );
                                            LinuxAccountHandler.setLinuxAccountOfficeLocation(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                location
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_ACCOUNT_OFFICE_PHONE :
                                        {
                                            String username=in.readUTF().trim();
                                            String phone=in.readUTF().trim();
                                            if(phone.length()==0) phone=null;
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_ACCOUNT_OFFICE_PHONE,
                                                username,
                                                phone
                                            );
                                            LinuxAccountHandler.setLinuxAccountOfficePhone(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                phone
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_ACCOUNT_SHELL :
                                        {
                                            String username=in.readUTF().trim();
                                            String shell=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_ACCOUNT_SHELL,
                                                username,
                                                shell
                                            );
                                            LinuxAccountHandler.setLinuxAccountShell(
                                                conn,
                                                source,
                                                invalidateList,
                                                username,
                                                shell
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_JUNK_EMAIL_RETENTION :
                                        {
                                            int pkey=in.readCompressedInt();
                                            int days=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_JUNK_EMAIL_RETENTION,
                                                Integer.valueOf(pkey),
                                                Integer.valueOf(days)
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountJunkEmailRetention(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                days
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_ACCOUNT_PASSWORD,
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountPassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_PREDISABLE_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                "set_linux_server_account_predisable_password",
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountPredisablePassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_EMAIL_SPAMASSASSIN_INTEGRATION_MODE:
                                        {
                                            int pkey=in.readCompressedInt();
                                            String mode=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_SPAMASSASSIN_INTEGRATION_MODE,
                                                Integer.valueOf(pkey),
                                                mode
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountSpamAssassinIntegrationMode(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                mode
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_SPAMASSASSIN_REQUIRED_SCORE:
                                        {
                                            int pkey=in.readCompressedInt();
                                            float required_score=in.readFloat();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_SPAMASSASSIN_REQUIRED_SCORE,
                                                Integer.valueOf(pkey),
                                                Float.valueOf(required_score)
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountSpamAssassinRequiredScore(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                required_score
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_TRASH_EMAIL_RETENTION :
                                        {
                                            int pkey=in.readCompressedInt();
                                            int days=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_TRASH_EMAIL_RETENTION,
                                                Integer.valueOf(pkey),
                                                Integer.valueOf(days)
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountTrashEmailRetention(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                days
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_LINUX_SERVER_ACCOUNT_USE_INBOX :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean useInbox=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_LINUX_SERVER_ACCOUNT_USE_INBOX,
                                                Integer.valueOf(pkey),
                                                useInbox?Boolean.TRUE:Boolean.FALSE
                                            );
                                            LinuxAccountHandler.setLinuxServerAccountUseInbox(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                useInbox
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_MAJORDOMO_INFO_FILE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String file=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_MAJORDOMO_INFO_FILE,
                                                Integer.valueOf(pkey),
                                                file
                                            );
                                            EmailHandler.setMajordomoInfoFile(
                                                conn,
                                                source,
                                                pkey,
                                                file
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_MAJORDOMO_INTRO_FILE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String file=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.SET_MAJORDOMO_INTRO_FILE,
                                                Integer.valueOf(pkey),
                                                file
                                            );
                                            EmailHandler.setMajordomoIntroFile(
                                                conn,
                                                source,
                                                pkey,
                                                file
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_MYSQL_SERVER_USER_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                AOSHCommand.SET_MYSQL_SERVER_USER_PASSWORD,
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            MySQLHandler.setMySQLServerUserPassword(
                                                conn,
                                                source,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_MYSQL_SERVER_USER_PREDISABLE_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                "set_mysql_server_user_predisable_password",
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            MySQLHandler.setMySQLServerUserPredisablePassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_NET_BIND_MONITORING :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean enabled=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_NET_BIND_MONITORING_ENABLED,
                                                Integer.valueOf(pkey),
                                                enabled?Boolean.TRUE:Boolean.FALSE
                                            );
                                            NetBindHandler.setNetBindMonitoringEnabled(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                enabled
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_NET_BIND_OPEN_FIREWALL :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean open_firewall=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.SET_NET_BIND_OPEN_FIREWALL,
                                                Integer.valueOf(pkey),
                                                open_firewall?Boolean.TRUE:Boolean.FALSE
                                            );
                                            NetBindHandler.setNetBindOpenFirewall(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                open_firewall
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_PACKAGE_DEFINITION_ACTIVE :
                                        {
                                            int pkey=in.readCompressedInt();
                                            boolean is_active=in.readBoolean();
                                            process.setCommand(
                                                "set_package_definition_active",
                                                Integer.valueOf(pkey),
                                                is_active?Boolean.TRUE:Boolean.FALSE
                                            );
                                            PackageHandler.setPackageDefinitionActive(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                is_active
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_PACKAGE_DEFINITION_LIMITS :
                                        {
                                            int pkey=in.readCompressedInt();
                                            int count=in.readCompressedInt();
                                            String[] resources=new String[count];
                                            int[] soft_limits=new int[count];
                                            int[] hard_limits=new int[count];
                                            int[] additional_rates=new int[count];
                                            String[] additional_transaction_types=new String[count];
                                            for(int c=0;c<count;c++) {
                                                resources[c]=in.readUTF().trim();
                                                soft_limits[c]=in.readCompressedInt();
                                                hard_limits[c]=in.readCompressedInt();
                                                additional_rates[c]=in.readCompressedInt();
                                                additional_transaction_types[c]=in.readBoolean()?in.readUTF().trim():null;
                                            }
                                            process.setCommand(
                                                "set_package_definition_limits",
                                                Integer.valueOf(pkey),
                                                Integer.valueOf(count),
                                                resources,
                                                soft_limits,
                                                hard_limits,
                                                additional_rates,
                                                additional_transaction_types
                                            );
                                            PackageHandler.setPackageDefinitionLimits(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                resources,
                                                soft_limits,
                                                hard_limits,
                                                additional_rates,
                                                additional_transaction_types
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_POSTGRES_SERVER_USER_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                AOSHCommand.SET_POSTGRES_SERVER_USER_PASSWORD,
                                                Integer.valueOf(pkey),
                                                password
                                            );
                                            PostgresHandler.setPostgresServerUserPassword(
                                                conn,
                                                source,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.SET_POSTGRES_SERVER_USER_PREDISABLE_PASSWORD :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String password=in.readBoolean()?in.readUTF():null;
                                            process.setCommand(
                                                "set_postgres_server_user_predisable_password",
                                                Integer.valueOf(pkey),
                                                AOServProtocol.FILTERED
                                            );
                                            PostgresHandler.setPostgresServerUserPredisablePassword(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                password
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_PRIMARY_HTTPD_SITE_URL :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.SET_PRIMARY_HTTPD_SITE_URL,
                                                Integer.valueOf(pkey)
                                            );
                                            HttpdHandler.setPrimaryHttpdSiteURL(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_PRIMARY_LINUX_GROUP_ACCOUNT :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.SET_PRIMARY_LINUX_GROUP_ACCOUNT,
                                                Integer.valueOf(pkey)
                                            );
                                            LinuxAccountHandler.setPrimaryLinuxGroupAccount(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_TICKET_ASSIGNED_TO :
                                        {
                                            int ticketID=in.readCompressedInt(); 
                                            String assignedTo=in.readUTF().trim();
                                            if(assignedTo.length()==0) assignedTo=null;
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                "set_ticket_assigned_to",
                                                Integer.valueOf(ticketID),
                                                assignedTo,
                                                username,
                                                comments
                                            );
                                            TicketHandler.setTicketAssignedTo(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                assignedTo,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_TICKET_CONTACT_EMAILS :
                                        {
                                            int ticketID=in.readCompressedInt(); 
                                            String contactEmails=in.readUTF().trim();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                "set_ticket_contact_emails",
                                                Integer.valueOf(ticketID),
                                                contactEmails,
                                                username,
                                                comments
                                            );
                                            TicketHandler.setTicketContactEmails(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                contactEmails,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_TICKET_CONTACT_PHONE_NUMBERS :
                                        {
                                            int ticketID=in.readCompressedInt(); 
                                            String contactPhoneNumbers=in.readUTF().trim();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                "set_ticket_contact_phone_numbers",
                                                Integer.valueOf(ticketID),
                                                contactPhoneNumbers,
                                                username,
                                                comments
                                            );
                                            TicketHandler.setTicketContactPhoneNumbers(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                contactPhoneNumbers,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.SET_TICKET_BUSINESS :
                                        {
                                            int ticketID=in.readCompressedInt(); 
                                            String accounting=in.readUTF().trim();
                                            if(accounting.length()==0) accounting=null;
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                "set_ticket_business",
                                                Integer.valueOf(ticketID),
                                                accounting,
                                                username,
                                                comments
                                            );
                                            TicketHandler.setTicketBusiness(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                accounting,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.START_APACHE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_APACHE,
                                                Integer.valueOf(aoServer)
                                            );
                                            HttpdHandler.startApache(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_CRON :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_CRON,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.startCron(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_DISTRO :
                                        {
                                            process.setPriority(Thread.MIN_PRIORITY+1);
                                            currentThread.setPriority(Thread.MIN_PRIORITY+1);

                                            int ao_server=in.readCompressedInt();
                                            boolean includeUser=in.readBoolean();
                                            process.setCommand(
                                                AOSHCommand.START_DISTRO,
                                                Integer.valueOf(ao_server),
                                                includeUser?Boolean.TRUE:Boolean.FALSE
                                            );
                                            AOServerHandler.startDistro(
                                                conn,
                                                source,
                                                ao_server,
                                                includeUser
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_INTERBASE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_INTERBASE,
                                                Integer.valueOf(aoServer)
                                            );
                                            InterBaseHandler.startInterBase(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_JVM :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_JVM,
                                                Integer.valueOf(pkey)
                                            );
                                            String message=HttpdHandler.startJVM(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=message!=null;
                                            hasResp2Boolean=true;
                                            if(message!=null) resp3String=message;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_MYSQL :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_MYSQL,
                                                Integer.valueOf(aoServer)
                                            );
                                            MySQLHandler.startMySQL(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_POSTGRESQL :
                                        {
                                            int postgresServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_POSTGRESQL,
                                                Integer.valueOf(postgresServer)
                                            );
                                            PostgresHandler.startPostgreSQL(
                                                conn,
                                                source,
                                                postgresServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_XFS :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_XFS,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.startXfs(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.START_XVFB :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.START_XVFB,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.startXvfb(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_APACHE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_APACHE,
                                                Integer.valueOf(aoServer)
                                            );
                                            HttpdHandler.stopApache(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_CRON :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_CRON,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.stopCron(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_INTERBASE :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_INTERBASE,
                                                Integer.valueOf(aoServer)
                                            );
                                            InterBaseHandler.stopInterBase(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_JVM :
                                        {
                                            int pkey=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_JVM,
                                                Integer.valueOf(pkey)
                                            );
                                            String message=HttpdHandler.stopJVM(
                                                conn,
                                                source,
                                                pkey
                                            );
                                            resp1=AOServProtocol.DONE;
                                            resp2Boolean=message!=null;
                                            hasResp2Boolean=true;
                                            if(message!=null) resp3String=message;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_MYSQL :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_MYSQL,
                                                Integer.valueOf(aoServer)
                                            );
                                            MySQLHandler.stopMySQL(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_POSTGRESQL :
                                        {
                                            int postgresServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_POSTGRESQL,
                                                Integer.valueOf(postgresServer)
                                            );
                                            PostgresHandler.stopPostgreSQL(
                                                conn,
                                                source,
                                                postgresServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_XFS :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_XFS,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.stopXfs(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.STOP_XVFB :
                                        {
                                            int aoServer=in.readCompressedInt();
                                            process.setCommand(
                                                AOSHCommand.STOP_XVFB,
                                                Integer.valueOf(aoServer)
                                            );
                                            AOServerHandler.stopXvfb(
                                                conn,
                                                source,
                                                aoServer
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                        }
                                        break;
                                    case AOServProtocol.TICKET_WORK :
                                        {
                                            int ticketID=in.readCompressedInt();
                                            String username=in.readUTF().trim();
                                            String comments=in.readUTF().trim();
                                            process.setCommand(
                                                AOSHCommand.ADD_TICKET_WORK,
                                                Integer.valueOf(ticketID),
                                                username,
                                                comments
                                            );
                                            TicketHandler.ticketWork(
                                                conn,
                                                source,
                                                invalidateList,
                                                ticketID,
                                                username,
                                                comments
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.TRANSACTION_APPROVED :
                                        {
                                            int transid=in.readCompressedInt();
                                            String paymentType=in.readUTF().trim();
                                            String paymentInfo=in.readBoolean()?in.readUTF().trim():null;
                                            String merchant=in.readBoolean()?in.readUTF().trim():null;
                                            String apr_num;
                                            if(AOServProtocol.compareVersions(source.getProtocolVersion(), AOServProtocol.VERSION_1_0_A_128)<0) apr_num=Integer.toString(in.readCompressedInt());
                                            else apr_num=in.readUTF();
                                            process.setCommand(
                                                AOSHCommand.APPROVE_TRANSACTION,
                                                Integer.valueOf(transid),
                                                paymentType,
                                                paymentInfo,
                                                merchant,
                                                apr_num
                                            );
                                            TransactionHandler.transactionApproved(
                                                conn,
                                                source,
                                                invalidateList,
                                                transid,
                                                paymentType,
                                                paymentInfo,
                                                merchant,
                                                apr_num
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.TRANSACTION_DECLINED :
                                        {
                                            int transid=in.readCompressedInt();
                                            String paymentType=in.readUTF().trim();
                                            String paymentInfo=in.readBoolean()?in.readUTF().trim():null;
                                            String merchant=in.readBoolean()?in.readUTF().trim():null;
                                            process.setCommand(
                                                AOSHCommand.DECLINE_TRANSACTION,
                                                Integer.valueOf(transid),
                                                paymentType,
                                                paymentInfo,
                                                merchant
                                            );
                                            TransactionHandler.transactionDeclined(
                                                conn,
                                                source,
                                                invalidateList,
                                                transid,
                                                paymentType,
                                                paymentInfo,
                                                merchant
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.UPDATE_HTTPD_TOMCAT_DATA_SOURCE:
                                        {
                                            int pkey=in.readCompressedInt();
                                            String name=in.readUTF();
                                            String driverClassName=in.readUTF();
                                            String url=in.readUTF();
                                            String username=in.readUTF();
                                            String password=in.readUTF();
                                            int maxActive=in.readCompressedInt();
                                            int maxIdle=in.readCompressedInt();
                                            int maxWait=in.readCompressedInt();
                                            String validationQuery=in.readUTF();
                                            if(validationQuery.length()==0) validationQuery=null;
                                            process.setCommand(
                                                AOSHCommand.UPDATE_HTTPD_TOMCAT_DATA_SOURCE,
                                                pkey,
                                                name,
                                                driverClassName,
                                                url,
                                                username,
                                                AOServProtocol.FILTERED,
                                                maxActive,
                                                maxIdle,
                                                maxWait,
                                                validationQuery
                                            );
                                            HttpdHandler.updateHttpdTomcatDataSource(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                name,
                                                driverClassName,
                                                url,
                                                username,
                                                password,
                                                maxActive,
                                                maxIdle,
                                                maxWait,
                                                validationQuery
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.UPDATE_HTTPD_TOMCAT_PARAMETER :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String name=in.readUTF();
                                            String value=in.readUTF();
                                            boolean override=in.readBoolean();
                                            String description=in.readUTF();
                                            if(description.length()==0) description=null;
                                            process.setCommand(
                                                AOSHCommand.UPDATE_HTTPD_TOMCAT_PARAMETER,
                                                pkey,
                                                name,
                                                value,
                                                override,
                                                description
                                            );
                                            HttpdHandler.updateHttpdTomcatParameter(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                name,
                                                value,
                                                override,
                                                description
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.UPDATE_PACKAGE_DEFINITION :
                                        {
                                            int pkey=in.readCompressedInt();
                                            String accounting=in.readUTF().trim();
                                            String category=in.readUTF().trim();
                                            String name=in.readUTF().trim();
                                            String version=in.readUTF().trim();
                                            String display=in.readUTF().trim();
                                            String description=in.readUTF().trim();
                                            int setupFee=in.readCompressedInt();
                                            String setupFeeTransactionType=in.readBoolean()?in.readUTF():null;
                                            int monthlyRate=in.readCompressedInt();
                                            String monthlyRateTransactionType=in.readUTF();
                                            process.setCommand(
                                                "update_package_definition",
                                                Integer.valueOf(pkey),
                                                accounting,
                                                category,
                                                name,
                                                version,
                                                display,
                                                description,
                                                SQLUtility.getDecimal(setupFee),
                                                setupFeeTransactionType,
                                                SQLUtility.getDecimal(monthlyRate),
                                                monthlyRateTransactionType
                                            );
                                            PackageHandler.updatePackageDefinition(
                                                conn,
                                                source,
                                                invalidateList,
                                                pkey,
                                                accounting,
                                                category,
                                                name,
                                                version,
                                                display,
                                                description,
                                                setupFee,
                                                setupFeeTransactionType,
                                                monthlyRate,
                                                monthlyRateTransactionType
                                            );
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=true;
                                        }
                                        break;
                                    case AOServProtocol.WAIT_FOR_REBUILD :
                                        {
                                            int clientTableID=in.readCompressedInt();
                                            SchemaTable.TableID tableID=TableHandler.convertFromClientTableID(conn, source, clientTableID);
                                            if(tableID==null) throw new IOException("Client table not supported: #"+clientTableID);
                                            int aoServer=in.readCompressedInt();
                                            switch(tableID) {
                                                case HTTPD_SITES :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_HTTPD_SITE_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    HttpdHandler.waitForHttpdSiteRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case INTERBASE_USERS :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_INTERBASE_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    InterBaseHandler.waitForInterBaseRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case LINUX_ACCOUNTS :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_LINUX_ACCOUNT_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    LinuxAccountHandler.waitForLinuxAccountRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case MYSQL_DATABASES :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_MYSQL_DATABASE_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    MySQLHandler.waitForMySQLDatabaseRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case MYSQL_DB_USERS :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_MYSQL_DB_USER_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    MySQLHandler.waitForMySQLDBUserRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case MYSQL_USERS :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_MYSQL_USER_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    MySQLHandler.waitForMySQLUserRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case POSTGRES_DATABASES :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_POSTGRES_DATABASE_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    PostgresHandler.waitForPostgresDatabaseRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case POSTGRES_SERVERS :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_POSTGRES_SERVER_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    PostgresHandler.waitForPostgresServerRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                case POSTGRES_USERS :
                                                    process.setCommand(
                                                        AOSHCommand.WAIT_FOR_POSTGRES_USER_REBUILD,
                                                        Integer.valueOf(aoServer)
                                                    );
                                                    PostgresHandler.waitForPostgresUserRebuild(
                                                        conn,
                                                        source,
                                                        aoServer
                                                    );
                                                    break;
                                                default :
                                                    throw new IOException("Unable to wait for rebuild on table: clientTableID="+clientTableID+", tableID="+tableID);
                                            }
                                            resp1=AOServProtocol.DONE;
                                            sendInvalidateList=false;
                                            break;
                                        }
                                    default :
                                        throw new IOException("Unknown task code: " + taskCode);
                                }

                                // Convert the invalidate list to client table IDs before releasing the connection
                                if(sendInvalidateList) {
                                    clientInvalidateList=new IntArrayList();
                                    for(SchemaTable.TableID tableID : SchemaTable.TableID.values()) {
                                        if(invalidateList.isInvalid(tableID)) clientInvalidateList.add(TableHandler.convertToClientTableID(conn, source, tableID));
                                    }
                                }
                            } catch (SQLException err) {
                                if(conn.rollbackAndClose()) {
                                    connRolledBack=true;
                                    invalidateList=null;
                                }
                                if(backupConn.rollbackAndClose()) {
                                    backupConnRolledBack=true;
                                    invalidateList=null;
                                }
                                throw err;
                            } catch(IOException err) {
                                if(conn.rollbackAndClose()) {
                                    connRolledBack=true;
                                    invalidateList=null;
                                }
                                if(backupConn.rollbackAndClose()) {
                                    backupConnRolledBack=true;
                                    invalidateList=null;
                                }
                                throw err;
                            } finally {
                                if(!connRolledBack && !conn.isClosed()) conn.commit();
                                if(!backupConnRolledBack && !backupConn.isClosed()) backupConn.commit();
                            }
                        } finally {
                            backupConn.releaseConnection();
                        }
                    } finally {
                        conn.releaseConnection();
                    }
                    // Invalidate the affected tables
                    if(invalidateList!=null) invalidateTables(invalidateList, source);

                    // Write the response codes
                    if(resp1!=-1) out.writeByte(resp1);
                    if(hasResp2Int) out.writeCompressedInt(resp2Int);
                    else if(hasResp2Long) out.writeLong(resp2Long);
                    else if(hasResp2Short) out.writeShort(resp2Short);
                    else if(resp2String!=null) out.writeUTF(resp2String);
                    else if(hasResp2Boolean) out.writeBoolean(resp2Boolean);
                    else if(hasResp2InboxAttributes) {
                        out.writeBoolean(resp2InboxAttributes!=null);
                        if(resp2InboxAttributes!=null) resp2InboxAttributes.write(out, source.getProtocolVersion());
                    } else if(hasResp2LongArray) {
                        for(int c=0;c<resp2LongArray.length;c++) out.writeLong(resp2LongArray[c]);
                    }
                    if(resp3String!=null) out.writeUTF(resp3String);
                    
                    // Write the invalidate list
                    if(sendInvalidateList) {
                        int numTables=clientInvalidateList.size();
                        for(int c=0;c<numTables;c++) {
                            int tableID=clientInvalidateList.getInt(c);
                            out.writeCompressedInt(tableID);
                        }
                        out.writeCompressedInt(-1);
                    }
                } catch(SQLException err) {
                    reportError(err, null);
                    String message=err.getMessage();
                    out.writeByte(AOServProtocol.SQL_EXCEPTION);
                    out.writeUTF(message==null?"":message);
                } catch(IOException err) {
                    reportError(err, null);
                    String message=err.getMessage();
                    out.writeByte(AOServProtocol.IO_EXCEPTION);
                    out.writeUTF(message==null?"":message);
                } finally {
                    if(currentThread.getPriority()!=Thread.NORM_PRIORITY) {
                        currentThread.setPriority(Thread.NORM_PRIORITY);
                        process.setPriority(Thread.NORM_PRIORITY);
                    }
                }
            }
            out.flush();
            if(addTime) addHistory(process);
            process.commandCompleted();
        } finally {
            if(addTime) {
                synchronized(MasterServer.class) {
                    concurrency--;
                    totalTime+=(System.currentTimeMillis()-requestStartTime);
                }
            }
        }
        return true;
    }

    /**
     * Invalidates a table by notifying all connected clients, except the client
     * that initiated this request.
     */
    public static void invalidateTables(
        InvalidateList invalidateList,
        RequestSource invalidateSource
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "invalidateTables(InvalidateList,RequestSource)", null);
        try {
            // Invalidate the internally cached data first
            invalidateList.invalidateMasterCaches();

            // Values used inside the loops
            long invalidateSourceConnectorID=invalidateSource==null?-1:invalidateSource.getConnectorID();

            IntList tableList=new IntArrayList();
            final MasterDatabaseConnection conn=(MasterDatabaseConnection)MasterDatabase.getDatabase().createDatabaseConnection();
            // Grab a copy of cacheListeners to help avoid deadlock
            List<RequestSource> listenerCopy=new ArrayList<RequestSource>(cacheListeners.size());
            synchronized(cacheListeners) {
                listenerCopy.addAll(cacheListeners);
            }
            Iterator<RequestSource> I=listenerCopy.iterator();
            while(I.hasNext()) {
                try {
                    RequestSource source=I.next();
                    if(invalidateSourceConnectorID!=source.getConnectorID()) {
                        tableList.clear();
                        // Build the list with a connection, but don't send until the connection is released
                        try {
                            try {
                                for(SchemaTable.TableID tableID : SchemaTable.TableID.values()) {
                                    int clientTableID=TableHandler.convertToClientTableID(conn, source, tableID);
                                    if(clientTableID!=-1) {
                                        List<String> affectedBusinesses=invalidateList.getAffectedBusinesses(tableID);
                                        List<String> affectedServers=invalidateList.getAffectedServers(tableID);
                                        if(
                                            affectedBusinesses!=null
                                            && affectedServers!=null
                                        ) {
                                            boolean businessMatches;
                                            int size=affectedBusinesses.size();
                                            if(size==0) businessMatches=true;
                                            else {
                                                businessMatches=false;
                                                for(int c=0;c<size;c++) {
                                                    if(BusinessHandler.canAccessBusiness(conn, source, affectedBusinesses.get(c))) {
                                                        businessMatches=true;
                                                        break;
                                                    }
                                                }
                                            }

                                            // Filter by server
                                            boolean serverMatches;
                                            size=affectedServers.size();
                                            if(size==0) serverMatches=true;
                                            else {
                                                serverMatches=false;
                                                for(int c=0;c<size;c++) {
                                                    String server=affectedServers.get(c);
                                                    if(ServerHandler.canAccessServer(conn, source, server)) {
                                                        serverMatches=true;
                                                        break;
                                                    }
                                                    if(
                                                        tableID==SchemaTable.TableID.AO_SERVERS
                                                        || tableID==SchemaTable.TableID.IP_ADDRESSES
                                                        || tableID==SchemaTable.TableID.LINUX_ACCOUNTS
                                                        || tableID==SchemaTable.TableID.LINUX_SERVER_ACCOUNTS
                                                        || tableID==SchemaTable.TableID.NET_DEVICES
                                                        || tableID==SchemaTable.TableID.SERVERS
                                                        || tableID==SchemaTable.TableID.USERNAMES
                                                    ) {
                                                        // These tables invalidations are also sent to the servers failover parent
                                                        int failoverServer=ServerHandler.getFailoverServer(conn, server);
                                                        if(failoverServer!=-1 && ServerHandler.canAccessServer(conn, source, failoverServer)) {
                                                            serverMatches=true;
                                                            break;
                                                        }
                                                    }
                                                }
                                            }


                                            // Send the invalidate through
                                            if(businessMatches && serverMatches) tableList.add(clientTableID);
                                        }
                                    }
                                }
                            } catch(SQLException err) {
                                conn.rollbackAndClose();
                                throw err;
                            }
                        } finally {
                            conn.releaseConnection();
                        }
                        source.cachesInvalidated(tableList);
                    }
                } catch(IOException err) {
                    reportError(err, null);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Runs all of the configured protocols of <code>MasterServer</code>
     * processes as configured in <code>com/aoindustries/aoserv/master/aoserv-master.properties</code>.
     */
    public static void main(String[] args) {
        // Not profiled because the profiler is enabled here
        try {
            Profiler.setProfilerLevel(MasterConfiguration.getProfilerLevel());

            // Configure the SSL
            synchronized(SSLServer.class) {
                if(!SSLServer.sslProviderLoaded[0]) {
                    boolean useSSL=false;
                    String trustStorePath=MasterConfiguration.getSSLTruststorePath();
                    if(trustStorePath!=null && trustStorePath.length()>0) {
                        System.setProperty("javax.net.ssl.trustStore", trustStorePath);
                        useSSL=true;
                    }
                    String trustStorePassword=MasterConfiguration.getSSLTruststorePassword();
                    if(trustStorePassword!=null && trustStorePassword.length()>0) {
                        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
                        useSSL=true;
                    }
                    String keyStorePath=MasterConfiguration.getSSLKeystorePath();
                    if(keyStorePath!=null && keyStorePath.length()>0) {
                        System.setProperty("javax.net.ssl.keyStore", keyStorePath);
                        useSSL=true;
                    }
                    String keyStorePassword=MasterConfiguration.getSSLKeystorePassword();
                    if(keyStorePassword!=null && keyStorePassword.length()>0) {
                        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
                        useSSL=true;
                    }
                    if(useSSL) {
                        Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
                        SSLServer.sslProviderLoaded[0]=true;
                    }
                }
            }

            List<String> protocols=MasterConfiguration.getProtocols();
            int protocolSize=protocols.size();
            for(int c=0;c<protocolSize;c++) {
                String protocol=protocols.get(c);
                List<String> binds=MasterConfiguration.getBinds(protocol);
                int port=MasterConfiguration.getPort(protocol);
                int size = binds.size();
                for (int d = 0; d < size; d++) {
                    String bind = binds.get(d);
                    if(TCPServer.PROTOCOL.equals(protocol)) new TCPServer(bind, port);
                    else if(SSLServer.PROTOCOL.equals(protocol)) new SSLServer(bind, port);
                    else throw new IllegalArgumentException("Unknown protocol: "+protocol);
                }
            }
            
            AccountCleaner.start();
            BackupCleaner.start();
            BackupDatabaseSynchronizer.start();
            DNSHandler.start();
            EmailHandler.start();
            FailoverHandler.start();
            ReportGenerator.start();
            TicketHandler.start();
        } catch (IOException err) {
            ErrorPrinter.printStackTraces(err);
        }
    }
    
    private static void removeCacheListener(RequestSource source) {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "removeCacheListener(RequestSource)", null);
        try {
            synchronized(cacheListeners) {
                int size=cacheListeners.size();
                for(int c=0;c<size;c++) {
                    RequestSource O=cacheListeners.get(c);
                    if(O==source) {
                        cacheListeners.remove(c);
                        break;
                    }
                }
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void reportError(Throwable T, Object[] extraInfo) {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "reportError(Throwable,Object[])", null);
        try {
            ErrorPrinter.printStackTraces(T, extraInfo);
            try {
                String smtp=MasterConfiguration.getErrorSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    List<String> addys=StringUtility.splitStringCommaSpace(MasterConfiguration.getErrorEmailTo());
                    String from=MasterConfiguration.getErrorEmailFrom();
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            T,
                            extraInfo,
                            smtp,
                            from,
                            addys.get(c),
                            "Master Bug"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void reportError(String message) {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "reportError(String)", null);
        try {
            System.err.println(message);
            try {
                String smtp=MasterConfiguration.getErrorSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    List<String> addys=StringUtility.splitStringCommaSpace(MasterConfiguration.getErrorEmailTo());
                    String from=MasterConfiguration.getErrorEmailFrom();
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            message,
                            smtp,
                            from,
                            addys.get(c),
                            "Master Bug"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.master.security.email.to</code>
     */
    public static void reportSecurityMessage(RequestSource source, String message) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "reportSecurityMessage(RequestSource,String)", null);
        try {
            reportSecurityMessage(source, message, true);
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * Logs a security message to <code>System.err</code>.
     * Also sends email messages to <code>aoserv.master.security.email.to</code>
     */
    public static void reportSecurityMessage(RequestSource source, String message, boolean sendEmail) {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "reportSecurityMessage(RequestSource,String,boolean)", null);
        try {
            reportSecurityMessage(message, sendEmail);
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }
    
    /**
     * Logs a security message to <code>System.err</code>
     * Also sends email messages to <code>aoserv.master.security.email.to</code>
     */
    public static void reportSecurityMessage(String message, boolean sendEmail) {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "reportSecurityMessage(String,boolean)", null);
        try {
            System.err.println(message);
            try {
                if(sendEmail) {
                    String smtp=MasterConfiguration.getErrorSmtpServer();
                    if(smtp!=null && smtp.length()>0) {
                        List<String> addys=StringUtility.splitStringCommaSpace(MasterConfiguration.getSecurityEmailTo());
                        String from=MasterConfiguration.getSecurityEmailFrom();
                        for(int c=0;c<addys.size();c++) {
                            ErrorMailer.reportError(
                                getRandom(),
                                message,
                                smtp,
                                from,
                                addys.get(c),
                                "Master Security"
                            );
                        }
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
    
    public static void reportWarning(Throwable T, Object[] extraInfo) {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "reportWarning(Throwable,Object[])", null);
        try {
            ErrorPrinter.printStackTraces(T, extraInfo);
            try {
                String smtp=MasterConfiguration.getWarningSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    List<String> addys=StringUtility.splitStringCommaSpace(MasterConfiguration.getWarningEmailTo());
                    String from=MasterConfiguration.getWarningEmailFrom();
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            T,
                            extraInfo,
                            smtp,
                            from,
                            addys.get(c),
                            "Master Warning"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static void reportWarning(String message) {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "reportWarning(String)", null);
        try {
            System.err.println(message);
            try {
                String smtp=MasterConfiguration.getWarningSmtpServer();
                if(smtp!=null && smtp.length()>0) {
                    List<String> addys=StringUtility.splitStringCommaSpace(MasterConfiguration.getWarningEmailTo());
                    String from=MasterConfiguration.getWarningEmailFrom();
                    for(int c=0;c<addys.size();c++) {
                        ErrorMailer.reportError(
                            getRandom(),
                            message,
                            smtp,
                            from,
                            addys.get(c),
                            "Master Warning"
                        );
                    }
                }
            } catch(IOException err) {
                ErrorPrinter.printStackTraces(err);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static ErrorHandler errorHandler;
    public synchronized static ErrorHandler getErrorHandler() {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "getErrorHandler()", null);
        try {
            if(errorHandler==null) {
                errorHandler=new ErrorHandler() {
                    public final void reportError(Throwable T, Object[] extraInfo) {
                        MasterServer.reportError(T, extraInfo);
                    }

                    public final void reportWarning(Throwable T, Object[] extraInfo) {
                        MasterServer.reportWarning(T, extraInfo);
                    }
                };
            }
            return errorHandler;
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Writes all rows of a results set.
     */
    public static <T extends AOServObject> void writeObjects(
        RequestSource source,
        CompressedDataOutputStream out,
        boolean provideProgress,
        T obj,
        ResultSet results
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObjects(RequestSource,CompressedDataOutputStream,boolean,<T extends AOServObject>,ResultSet)", null);
        try {
            String version=source.getProtocolVersion();

            // Make one pass counting the rows if providing progress information
            if(provideProgress) {
                int rowCount = 0;
                while (results.next()) rowCount++;
                results.beforeFirst();
                out.writeByte(AOServProtocol.NEXT);
                out.writeCompressedInt(rowCount);
            }
            int writeCount = 0;
            while(results.next()) {
                obj.init(results);
                out.writeByte(AOServProtocol.NEXT);
                obj.write(out, version);
                writeCount++;
            }
            if(writeCount > TableHandler.RESULT_SET_BATCH_SIZE) reportWarning(new SQLWarning("Warning: provideProgress==true caused non-cursor select with more than "+TableHandler.RESULT_SET_BATCH_SIZE+" rows: "+writeCount), null);
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Writes all rows of a results set.
     */
    public static void writeObjects(RequestSource source, CompressedDataOutputStream out, boolean provideProgress, List<? extends AOServObject> objs) throws IOException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObjects(RequestSource,CompressedDataOutputStream,boolean,List<? extends AOServObject>)", null);
        try {
            String version=source.getProtocolVersion();

            int size=objs.size();
            if (provideProgress) {
                out.writeByte(AOServProtocol.NEXT);
                out.writeCompressedInt(size);
            }
            for(int c=0;c<size;c++) {
                out.writeByte(AOServProtocol.NEXT);
                objs.get(c).write(out, version);
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    /**
     * Writes all rows of a results set.
     */
    public static void writeObjectsSynced(RequestSource source, CompressedDataOutputStream out, boolean provideProgress, List<? extends AOServObject> objs) throws IOException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObjectsSynched(RequestSource,CompressedDataOutputStream,boolean,List<? extends AOServObject>)", null);
        try {
            String version=source.getProtocolVersion();

            int size=objs.size();
            if (provideProgress) {
                out.writeByte(AOServProtocol.NEXT);
                out.writeCompressedInt(size);
            }
            for(int c=0;c<size;c++) {
                out.writeByte(AOServProtocol.NEXT);
                AOServObject obj=objs.get(c);
                synchronized(obj) {
                    obj.write(out, version);
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static String authenticate(
        MasterDatabaseConnection conn,
        String remoteHost, 
        String connectAs, 
        String authenticateAs, 
        String password
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "authenticate(MasterDatabaseConnection,String,String,String,String)", null);
        try {
            if(connectAs.length()==0) return "Connection attempted with empty connect username";
            if(authenticateAs.length()==0) return "Connection attempted with empty authentication username";

            if(!BusinessHandler.isBusinessAdministrator(conn, authenticateAs)) return "Unable to find BusinessAdministrator: "+authenticateAs;

            if(BusinessHandler.isBusinessAdministratorDisabled(conn, authenticateAs)) return "BusinessAdministrator disabled: "+authenticateAs;

            if (!isHostAllowed(conn, authenticateAs, remoteHost)) return "Connection from "+remoteHost+" as "+authenticateAs+" not allowed.";

            // Authenticate the client first
            if(password.length()==0) return "Connection attempted with empty password";

            String correctCrypted=BusinessHandler.getBusinessAdministrator(conn, authenticateAs).getPassword();
            if(
                correctCrypted==null
                || correctCrypted.length()<=2
                || !UnixCrypt.crypt(password, correctCrypted.substring(0,2)).equals(correctCrypted)
            ) return "Connection attempted with invalid password";

            // If connectAs is not authenticateAs, must be authenticated with switch user permissions
            if(!connectAs.equals(authenticateAs)) {
                // Must have can_switch_users permissions and must be switching to a subaccount user
                if(!BusinessHandler.canSwitchUser(conn, authenticateAs, connectAs)) return "Not allowed to switch users from "+authenticateAs+" to "+connectAs;
            }

            // Let them in
            return null;
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static String trim(String inStr) {
        return (inStr==null?null:inStr.trim());
    }

    private static final Object historyLock=new Object();
    private static MasterHistory[] masterHistory;
    private static int masterHistoryStart=0;

    private static void addHistory(
        MasterProcess process
    ) throws IOException {
        synchronized(historyLock) {
            if(masterHistory==null) masterHistory=new MasterHistory[
                MasterConfiguration.getHistorySize()
            ];
            masterHistory[masterHistoryStart]=new MasterHistory(
                process.getProcessID(),
                process.getConnectorID(),
                process.getAuthenticatedUser(),
                process.getEffectiveUser(),
                process.getHost(),
                process.getProtocol(),
                process.isSecure(),
                process.getStateStartTime(),
                System.currentTimeMillis(),
                process.getCommand()
            );
            masterHistoryStart++;
            if(masterHistoryStart>=masterHistory.length) {
                masterHistoryStart=0;
            }
        }
    }

    public static void writeHistory(
        MasterDatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        boolean provideProgress,
        MasterUser masterUser,
        com.aoindustries.aoserv.client.MasterServer[] masterServers
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "writeHistory(MasterDatabaseConnection,RequestSource,CompressedDataOutputStream,boolean,MasterUser,MasterServer[])", null);
        try {
            // Create the list of objects first
            List<MasterHistory> objs=new ArrayList<MasterHistory>();
            synchronized(historyLock) {
                // Grab a copy of the history
                MasterHistory[] history=masterHistory;
                if(history!=null) {
                    int historyLen=history.length;
                    //objs.ensureCapacity(historyLen);
                    int startPos=masterHistoryStart;
                    for(int c=0;c<historyLen;c++) {
                        MasterHistory mh=history[(c+startPos)%historyLen];
                        if(mh!=null) {
                            if(masterUser!=null && masterServers.length==0) {
                                objs.add(mh);
                            } else {
                                if(UsernameHandler.canAccessUsername(conn, source, mh.getEffectiveUser())) objs.add(mh);
                            }
                        }
                    }
                }
            }
            writeObjects(source, out, provideProgress, objs);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    private static void addStat(
        List<MasterServerStat> objs, 
        String name, 
        String value, 
        String description
    ) {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "addStat(List<MasterServerStat>,String,String,String)", null);
        name=trim(name);
        value=trim(value);
        description=trim(description);
        try {
            objs.add(new MasterServerStat(name, value, description));
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void writeStats(RequestSource source, CompressedDataOutputStream out, boolean provideProgress) throws IOException {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "writeStats(RequestSource,CompressedDataOutputStream,boolean)", null);
        try {
            try {
                // Create the list of objects first
                List<MasterServerStat> objs=new ArrayList<MasterServerStat>();
                addStat(objs, MasterServerStat.BYTE_ARRAY_CACHE_CONCURRENCY, Integer.toString(BufferManager.getByteBufferUsedCount()), "Current number of byte[] buffers allocated");
                addStat(objs, MasterServerStat.BYTE_ARRAY_CACHE_SIZE, Integer.toString(BufferManager.getByteBufferCount()), "Number of byte[] buffers created");
                addStat(objs, MasterServerStat.BYTE_ARRAY_CACHE_USES, Long.toString(BufferManager.getByteBufferUses()), "Total number of byte[] buffers allocated");

                addStat(objs, MasterServerStat.CHAR_ARRAY_CACHE_CONCURRENCY, Integer.toString(BufferManager.getCharBufferUsedCount()), "Current number of char[] buffers allocated");
                addStat(objs, MasterServerStat.CHAR_ARRAY_CACHE_SIZE, Integer.toString(BufferManager.getCharBufferCount()), "Number of char[] buffers created");
                addStat(objs, MasterServerStat.CHAR_ARRAY_CACHE_USES, Long.toString(BufferManager.getCharBufferUses()), "Total number of char[] buffers allocated");

                addStat(objs, MasterServerStat.DAEMON_CONCURRENCY, Integer.toString(DaemonHandler.getDaemonConcurrency()), "Number of active daemon connections");
                addStat(objs, MasterServerStat.DAEMON_CONNECTIONS, Integer.toString(DaemonHandler.getDaemonConnections()), "Current number of daemon connections");
                addStat(objs, MasterServerStat.DAEMON_CONNECTS, Integer.toString(DaemonHandler.getDaemonConnects()), "Number of times connecting to daemons");
                addStat(objs, MasterServerStat.DAEMON_COUNT, Integer.toString(DaemonHandler.getDaemonCount()), "Number of daemons that have been accessed");
                addStat(objs, MasterServerStat.DAEMON_DOWN_COUNT, Integer.toString(DaemonHandler.getDownDaemonCount()), "Number of daemons that are currently unavailable");
                addStat(objs, MasterServerStat.DAEMON_MAX_CONCURRENCY, Integer.toString(DaemonHandler.getDaemonMaxConcurrency()), "Peak number of active daemon connections");
                addStat(objs, MasterServerStat.DAEMON_POOL_SIZE, Integer.toString(DaemonHandler.getDaemonPoolSize()), "Maximum number of daemon connections");
                addStat(objs, MasterServerStat.DAEMON_TOTAL_TIME, StringUtility.getDecimalTimeLengthString(DaemonHandler.getDaemonTotalTime()), "Total time spent accessing daemons");
                addStat(objs, MasterServerStat.DAEMON_TRANSACTIONS, Long.toString(DaemonHandler.getDaemonTransactions()), "Number of transactions processed by daemons");

                AOConnectionPool dbPool=MasterDatabase.getDatabase().getConnectionPool();
                addStat(objs, MasterServerStat.DB_CONCURRENCY, Integer.toString(dbPool.getConcurrency()), "Number of active database connections");
                addStat(objs, MasterServerStat.DB_CONNECTIONS, Integer.toString(dbPool.getConnectionCount()), "Current number of database connections");
                addStat(objs, MasterServerStat.DB_CONNECTS, Long.toString(dbPool.getConnects()), "Number of times connecting to the database");
                addStat(objs, MasterServerStat.DB_MAX_CONCURRENCY, Integer.toString(dbPool.getMaxConcurrency()), "Peak number of active database connections");
                addStat(objs, MasterServerStat.DB_POOL_SIZE, Integer.toString(dbPool.getPoolSize()), "Maximum number of database connections");
                addStat(objs, MasterServerStat.DB_QUERIES, Long.toString(MasterDatabase.getDatabase().getQueryCount()), "Number of queries performed by the database");
                addStat(objs, MasterServerStat.DB_TOTAL_TIME, StringUtility.getDecimalTimeLengthString(dbPool.getTotalTime()), "Total time spent accessing the database");
                addStat(objs, MasterServerStat.DB_TRANSACTIONS, Long.toString(dbPool.getTransactionCount()), "Number of transactions committed by the database");
                addStat(objs, MasterServerStat.DB_UPDATES, Long.toString(MasterDatabase.getDatabase().getUpdateCount()), "Number of updates processed by the database");

                FifoFile entropyFile=RandomHandler.getFifoFile();
                addStat(objs, MasterServerStat.ENTROPY_AVAIL, Long.toString(entropyFile.getLength()), "Number of bytes of entropy currently available");
                addStat(objs, MasterServerStat.ENTROPY_POOLSIZE, Long.toString(entropyFile.getMaximumFifoLength()), "Maximum number of bytes of entropy");
                FifoFileInputStream entropyIn=entropyFile.getInputStream();
                addStat(objs, MasterServerStat.ENTROPY_READ_BYTES, Long.toString(entropyIn.getReadBytes()), "Number of bytes read from the entropy pool");
                addStat(objs, MasterServerStat.ENTROPY_READ_COUNT, Long.toString(entropyIn.getReadCount()), "Number of reads from the entropy pool");
                FifoFileOutputStream entropyOut=entropyFile.getOutputStream();
                addStat(objs, MasterServerStat.ENTROPY_WRITE_BYTES, Long.toString(entropyOut.getWriteBytes()), "Number of bytes written to the entropy pool");
                addStat(objs, MasterServerStat.ENTROPY_WRITE_COUNT, Long.toString(entropyOut.getWriteCount()), "Number of writes to the entropy pool");

                addStat(objs, MasterServerStat.MEMORY_FREE, Long.toString(Runtime.getRuntime().freeMemory()), "Free virtual machine memory in bytes");
                addStat(objs, MasterServerStat.MEMORY_TOTAL, Long.toString(Runtime.getRuntime().totalMemory()), "Total virtual machine memory in bytes");

                addStat(objs, MasterServerStat.METHOD_CONCURRENCY, Integer.toString(Profiler.getConcurrency()), "Current number of virtual machine methods in use");
                addStat(objs, MasterServerStat.METHOD_MAX_CONCURRENCY, Integer.toString(Profiler.getMaxConcurrency()), "Peak number of virtual machine methods in use");
                addStat(objs, MasterServerStat.METHOD_PROFILE_LEVEL, Integer.toString(Profiler.getProfilerLevel()), "Current method profiling level");
                addStat(objs, MasterServerStat.METHOD_USES, Long.toString(Profiler.getMethodUses()), "Number of virtual machine methods invoked");

                addStat(objs, MasterServerStat.PROTOCOL_VERSION, StringUtility.buildList(AOServProtocol.getVersions()), "Supported AOServProtocol version numbers");

                addStat(objs, MasterServerStat.REQUEST_CONCURRENCY, Integer.toString(getRequestConcurrency()), "Current number of client requests being processed");
                addStat(objs, MasterServerStat.REQUEST_CONNECTIONS, Long.toString(getRequestConnections()), "Number of connections received from clients");
                addStat(objs, MasterServerStat.REQUEST_MAX_CONCURRENCY, Integer.toString(getRequestMaxConcurrency()), "Peak number of client requests being processed");
                addStat(objs, MasterServerStat.REQUEST_TOTAL_TIME, StringUtility.getDecimalTimeLengthString(getRequestTotalTime()), "Total time spent processing client requests");
                addStat(objs, MasterServerStat.REQUEST_TRANSACTIONS, Long.toString(getRequestTransactions()), "Number of client requests processed");

                addStat(objs, MasterServerStat.THREAD_COUNT, Integer.toString(ThreadUtility.getThreadCount()), "Current number of virtual machine threads");

                addStat(objs, MasterServerStat.UPTIME, StringUtility.getDecimalTimeLengthString(System.currentTimeMillis()-getStartTime()), "Amount of time the master server has been running");

                writeObjects(source, out, provideProgress, objs);
            } catch(IOException err) {
                reportError(err, null);
                out.writeByte(AOServProtocol.IO_EXCEPTION);
                String message=err.getMessage();
                out.writeUTF(message==null?"":message);
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    /**
     * @see  #checkAccessHostname(MasterDatabaseConnection,RequestSource,String,String,String[])
     */
    public static void checkAccessHostname(MasterDatabaseConnection conn, RequestSource source, String action, String hostname) throws IOException, SQLException {
        Profiler.startProfile(Profiler.INSTANTANEOUS, MasterServer.class, "checkAccessHostname(MasterDatabaseConnection,RequestSource,String,String)", null);
        try {
            checkAccessHostname(conn, source, action, hostname, DNSHandler.getDNSTLDs(conn));
        } finally {
            Profiler.endProfile(Profiler.INSTANTANEOUS);
        }
    }

    /**
     * Determines if this hostname may be used by the source.  The dns_forbidden_zones,
     * dns_zones, httpd_site_urls, and email_domains tables are searched, in this order,
     * for a match.  If a match is found with an owner of this source, then access is
     * granted.  If the source is not restricted by either server or business, then
     * access is granted and the previous checks are avoided.
     */
    public static void checkAccessHostname(MasterDatabaseConnection conn, RequestSource source, String action, String hostname, List<String> tlds) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "checkAccessHostname(MasterDatabaseConnection,RequestSource,String,String,List<String>)", null);
        try {
            try {
                String zone = DNSZoneTable.getDNSZoneForHostname(hostname, tlds);

                if(conn.executeBooleanQuery(
                    "select (select zone from dns_forbidden_zones where zone=?) is not null",
                    zone
                )) throw new SQLException("Access to this hostname forbidden: Exists in dns_forbidden_zones: "+hostname);

                String username = source.getUsername();

                String existingZone=conn.executeStringQuery(
                    Connection.TRANSACTION_READ_COMMITTED,
                    true,
                    false,
                    "select zone from dns_zones where zone=?",
                    zone
                );
                if(existingZone!=null && !DNSHandler.canAccessDNSZone(conn, source, existingZone)) throw new SQLException("Access to this hostname forbidden: Exists in dns_zones: "+hostname);

                String domain = zone.substring(0, zone.length()-1);

                IntList httpdSites=conn.executeIntListQuery(
                    "select\n"
                    + "  hsb.httpd_site\n"
                    + "from\n"
                    + "  httpd_site_urls hsu,\n"
                    + "  httpd_site_binds hsb\n"
                    + "where\n"
                    + "  (hsu.hostname=? or hsu.hostname like ?)\n"
                    + "  and hsu.httpd_site_bind=hsb.pkey",
                    domain,
                    "%."+domain
                );
                // Must be able to access all of the sites
                for(int httpdSite : httpdSites) if(!HttpdHandler.canAccessHttpdSite(conn, source, httpdSite)) throw new SQLException("Access to this hostname forbidden: Exists in httpd_site_urls: "+hostname);

                IntList emailDomains=conn.executeIntListQuery(
                    "select pkey from email_domains where (domain=? or domain like ?)",
                    domain,
                    "%."+domain
                );
                // Must be able to access all of the domains
                for(int emailDomain : emailDomains) if(!EmailHandler.canAccessEmailDomain(conn, source, emailDomain)) throw new SQLException("Access to this hostname forbidden: Exists in email_domains: "+hostname);
            } catch(IllegalArgumentException err) {
                SQLException sqlErr=new SQLException();
                sqlErr.initCause(err);
                throw sqlErr;
            }
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }

    public static com.aoindustries.aoserv.client.MasterServer[] getMasterServers(MasterDatabaseConnection conn, String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "getMasterServers(MasterDatabaseConnection,String)", null);
        try {
	    synchronized(MasterServer.class) {
		if(masterServers==null) masterServers=new HashMap<String,com.aoindustries.aoserv.client.MasterServer[]>();
		com.aoindustries.aoserv.client.MasterServer[] mss=masterServers.get(username);
		if(mss!=null) return mss;
		PreparedStatement pstmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement("select ms.* from master_users mu, master_servers ms where mu.is_active and mu.username=? and mu.username=ms.username");
		try {
		    List<com.aoindustries.aoserv.client.MasterServer> v=new ArrayList<com.aoindustries.aoserv.client.MasterServer>();
		    pstmt.setString(1, username);
		    conn.incrementQueryCount();
		    ResultSet results=pstmt.executeQuery();
		    while(results.next()) {
			com.aoindustries.aoserv.client.MasterServer ms=new com.aoindustries.aoserv.client.MasterServer();
			ms.init(results);
			v.add(ms);
		    }
		    mss=new com.aoindustries.aoserv.client.MasterServer[v.size()];
		    v.toArray(mss);
		    masterServers.put(username, mss);
		    return mss;
		} finally {
		    pstmt.close();
		}
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static MasterUser getMasterUser(MasterDatabaseConnection conn, String username) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "getMasterUser(MasterDatabaseConnection,String)", null);
        try {
	    synchronized(MasterServer.class) {
		if(masterUsers==null) {
		    Statement stmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).createStatement();
		    try {
			Map<String,MasterUser> table=new HashMap<String,MasterUser>();
			conn.incrementQueryCount();
			ResultSet results=stmt.executeQuery("select * from master_users where is_active");
			while(results.next()) {
			    MasterUser mu=new MasterUser();
			    mu.init(results);
			    table.put(results.getString(1), mu);
			}
			masterUsers=table;
		    } finally {
			stmt.close();
		    }
		}
		return masterUsers.get(username);
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    /**
     * Gets the hosts that are allowed for the provided username.
     */
    public static boolean isHostAllowed(MasterDatabaseConnection conn, String username, String host) throws IOException, SQLException {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "isHostAllowed(MasterDatabaseConnection,String,String)", null);
        try {
	    synchronized(MasterServer.class) {
		if(masterHosts==null) {
		    Statement stmt=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).createStatement();
		    try {
			Map<String,List<String>> table=new HashMap<String,List<String>>();
			conn.incrementQueryCount();
			ResultSet results=stmt.executeQuery("select mh.username, mh.host from master_hosts mh, master_users mu where mh.username=mu.username and mu.is_active");
			while(results.next()) {
			    String un=results.getString(1);
			    String ho=results.getString(2);
			    List<String> sv=table.get(un);
			    if(sv==null) table.put(un, sv=new SortedArrayList<String>());
			    sv.add(ho);
			}
			masterHosts=table;
		    } finally {
			stmt.close();
		    }
		}
		if(getMasterUser(conn, username)!=null) {
		    List<String> hosts=masterHosts.get(username);
		    // Allow from anywhere if no hosts are provided
		    if(hosts==null) return true;
		    String remoteHost=InetAddress.getByName(host).getHostAddress();
		    int size = hosts.size();
		    for (int c = 0; c < size; c++) {
			String tempAddress = InetAddress.getByName(hosts.get(c)).getHostAddress();
			if (tempAddress.equals(remoteHost)) return true;
		    }
		    return false;
		} else {
		    // Normal users can connect from any where
		    return BusinessHandler.getBusinessAdministrator(conn, username)!=null;
		}
	    }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void writeObject(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        String sql,
        int param1,
        String param2,
        AOServObject obj
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObject(DatabaseConnection,RequestSource,CompressedDataOutputStream,String,int,String,AOServObject)", null);
        try {
            String version=source.getProtocolVersion();

            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setInt(1, param1);
                pstmt.setString(2, param2);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        obj.init(results);
                        out.writeByte(AOServProtocol.NEXT);
                        obj.write(out, version);
                    } else out.writeByte(AOServProtocol.DONE);
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writeObject(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        String sql,
        int param1,
        AOServObject obj
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObject(DatabaseConnection,RequestSource,CompressedDataOutputStream,String,int,AOServObject)", null);
        try {
            String version=source.getProtocolVersion();

            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setInt(1, param1);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        obj.init(results);
                        out.writeByte(AOServProtocol.NEXT);
                        obj.write(out, version);
                    } else out.writeByte(AOServProtocol.DONE);
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writeObject(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        String sql,
        String param1,
        int param2,
        AOServObject obj
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObject(DatabaseConnection,RequestSource,CompressedDataOutputStream,String,String,int,AOServObject)", null);
        try {
            String version=source.getProtocolVersion();

            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setString(1, param1);
                pstmt.setInt(2, param2);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        obj.init(results);
                        out.writeByte(AOServProtocol.NEXT);
                        obj.write(out, version);
                    } else out.writeByte(AOServProtocol.DONE);
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writeObject(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        String sql,
        int param1,
        String param2,
        String param3,
        String param4,
        String param5,
        AOServObject obj
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObject(DatabaseConnection,RequestSource,CompressedDataOutputStream,String,int,String,String,String,String,AOServObject)", null);
        try {
            String version=source.getProtocolVersion();

            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setInt(1, param1);
                pstmt.setString(2, param2);
                pstmt.setString(3, param3);
                pstmt.setString(4, param4);
                pstmt.setString(5, param5);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        obj.init(results);
                        out.writeByte(AOServProtocol.NEXT);
                        obj.write(out, version);
                    } else out.writeByte(AOServProtocol.DONE);
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writeObject(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        String sql,
        String param1,
        int param2,
        String param3,
        int param4,
        String param5,
        int param6,
        String param7,
        int param8,
        AOServObject obj
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObject(DatabaseConnection,RequestSource,CompressedDataOutputStream,String,String,int,String,int,String,int,String,int,AOServObject)", null);
        try {
            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setString(1, param1);
                pstmt.setInt(2, param2);
                pstmt.setString(3, param3);
                pstmt.setInt(4, param4);
                pstmt.setString(5, param5);
                pstmt.setInt(6, param6);
                pstmt.setString(7, param7);
                pstmt.setInt(8, param8);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        obj.init(results);
                        out.writeByte(AOServProtocol.NEXT);
                        obj.write(out, source.getProtocolVersion());
                    } else out.writeByte(AOServProtocol.DONE);
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void fetchObjects(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        AOServObject obj,
        String sql,
        Object ... params
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "fetchObjects(DatabaseConnection,RequestSource,CompressedDataOutputStream,AOServObject,String,...)", null);
        try {
            String version=source.getProtocolVersion();

            Connection dbConn=conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, false);

            PreparedStatement pstmt=dbConn.prepareStatement("declare fetch_objects cursor for "+sql);
            try {
                DatabaseConnection.setParams(pstmt, params);
                conn.incrementUpdateCount();
                pstmt.executeUpdate();
            } catch(SQLException err) {
                System.err.println("Error from select: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }

            String sqlString="fetch "+TableHandler.RESULT_SET_BATCH_SIZE+" from fetch_objects";
            Statement stmt = dbConn.createStatement();
            try {
                while(true) {
                    int batchSize=0;
                    ResultSet results=stmt.executeQuery(sqlString);
                    try {
                        while(results.next()) {
                            obj.init(results);
                            out.writeByte(AOServProtocol.NEXT);
                            obj.write(out, version);
                            batchSize++;
                        }
                    } finally {
                        results.close();
                    }
                    if(batchSize<TableHandler.RESULT_SET_BATCH_SIZE) break;
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+sqlString);
                throw err;
            } finally {
                stmt.executeUpdate("close fetch_objects");
                stmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writeObjects(
        DatabaseConnection conn,
        RequestSource source,
        CompressedDataOutputStream out,
        boolean provideProgress,
        AOServObject obj,
        String sql,
        Object ... params
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writeObjects(DatabaseConnection,RequestSource,CompressedDataOutputStream,boolean,AOServObject,String,...)", null);
        try {
            if(!provideProgress) fetchObjects(conn, source, out, obj, sql, params);
            else {
                PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
                try {
                    DatabaseConnection.setParams(pstmt, params);
                    conn.incrementQueryCount();
                    ResultSet results = pstmt.executeQuery();
                    try {
                        writeObjects(source, out, provideProgress, obj, results);
                    } finally {
                        results.close();
                    }
                } catch(SQLException err) {
                    System.err.println("Error from query: "+pstmt.toString());
                    throw err;
                } finally {
                    pstmt.close();
                }
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writePenniesCheckBusiness(
        MasterDatabaseConnection conn,
        RequestSource source,
        String action,
        String accounting,
        CompressedDataOutputStream out,
        String sql,
        String param1
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writePenniesCheckBusiness(MasterDatabaseConnection,RequestSource,String,String,CompressedDataOutputStream,String,String)", null);
        try {
            BusinessHandler.checkAccessBusiness(conn, source, action, accounting);
            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setString(1, param1);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        out.writeByte(AOServProtocol.DONE);
                        out.writeCompressedInt(SQLUtility.getPennies(results.getString(1)));
                    } else throw new SQLException("No row returned.");
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void writePenniesCheckBusiness(
        MasterDatabaseConnection conn,
        RequestSource source,
        String action,
        String accounting,
        CompressedDataOutputStream out,
        String sql,
        String param1,
        Timestamp param2
    ) throws IOException, SQLException {
        Profiler.startProfile(Profiler.IO, MasterServer.class, "writePenniesCheckBusiness(MasterDatabaseConnection,RequestSource,String,String,CompressedDataOutputStream,String,String,Timestamp)", null);
        try {
            BusinessHandler.checkAccessBusiness(conn, source, action, accounting);
            PreparedStatement pstmt = conn.getConnection(Connection.TRANSACTION_READ_COMMITTED, true).prepareStatement(sql);
            try {
                pstmt.setString(1, param1);
                pstmt.setTimestamp(2, param2);
                conn.incrementQueryCount();
                ResultSet results=pstmt.executeQuery();
                try {
                    if(results.next()) {
                        out.writeByte(AOServProtocol.DONE);
                        out.writeCompressedInt(SQLUtility.getPennies(results.getString(1)));
                    } else throw new SQLException("No row returned.");
                } finally {
                    results.close();
                }
            } catch(SQLException err) {
                System.err.println("Error from query: "+pstmt.toString());
                throw err;
            } finally {
                pstmt.close();
            }
        } finally {
            Profiler.endProfile(Profiler.IO);
        }
    }

    public static void invalidateTable(SchemaTable.TableID tableID) {
        Profiler.startProfile(Profiler.FAST, MasterServer.class, "invalidateTable(SchemaTable.TableID)", null);
        try {
            if(tableID==SchemaTable.TableID.MASTER_HOSTS) {
                synchronized(MasterServer.class) {
                    masterHosts=null;
                }
            } else if(tableID==SchemaTable.TableID.MASTER_SERVERS) {
                synchronized(MasterServer.class) {
                    masterHosts=null;
                    masterServers=null;
                }
            } else if(tableID==SchemaTable.TableID.MASTER_USERS) {
                synchronized(MasterServer.class) {
                    masterHosts=null;
                    masterServers=null;
                    masterUsers=null;
                }
            }
        } finally {
            Profiler.endProfile(Profiler.FAST);
        }
    }

    public static void updateAOServProtocolLastUsed(MasterDatabaseConnection conn, String protocolVersion) throws IOException, SQLException {
        Profiler.startProfile(Profiler.UNKNOWN, MasterServer.class, "updateAOServProtocolLastUsed(MasterDatabaseConnection,String)", null);
        try {
            conn.executeUpdate("update aoserv_protocols set last_used=now()::date where version=? and (last_used is null or last_used<now()::date)", protocolVersion);
        } finally {
            Profiler.endProfile(Profiler.UNKNOWN);
        }
    }
}
