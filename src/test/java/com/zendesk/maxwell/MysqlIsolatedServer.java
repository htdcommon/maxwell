package com.zendesk.maxwell;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zendesk.maxwell.replication.MysqlVersion;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class MysqlIsolatedServer {
	public static final Long SERVER_ID = 4321L;

	public static final MysqlVersion VERSION_5_5 = new MysqlVersion(5, 5);
	public static final MysqlVersion VERSION_5_6 = new MysqlVersion(5, 6);
	public static final MysqlVersion VERSION_5_7 = new MysqlVersion(5, 7);

	private Connection connection;
	private int port;
	private int serverPid;
	public String path;

	static final Logger LOGGER = LoggerFactory.getLogger(MysqlIsolatedServer.class);
	public static final TypeReference<Map<String, Object>> MAP_STRING_OBJECT_REF = new TypeReference<Map<String, Object>>() {};

	public void boot(String xtraParams) throws IOException, SQLException, InterruptedException {
        final String dir = System.getProperty("user.dir");

		if ( xtraParams == null )
			xtraParams = "";

		// By default, MySQL doesn't run under root. However, in an environment like Docker, the root user is the
		// only available user by default. By adding "--user=root" when the root user is used, we can make sure
		// the tests can continue to run.
		boolean isRoot = System.getProperty("user.name").equals("root");
		System.out.println(isRoot);

		String gtidParams = "";
		if (MaxwellTestSupport.inGtidMode()) {
			LOGGER.info("In gtid test mode.");
			gtidParams =
				"--gtid-mode=ON " +
				"--log-slave-updates=ON " +
				"--enforce-gtid-consistency=true ";
		}
		String serverID = "";
		if ( !xtraParams.contains("--server_id") )
			serverID = "--server_id=" + SERVER_ID;

		ProcessBuilder pb = new ProcessBuilder(
			dir + "/src/test/onetimeserver",
			"--mysql-version=" + this.getVersionString(),
			"--log-slave-updates",
			"--log-bin=master",
			"--binlog_format=row",
			"--innodb_flush_log_at_trx_commit=0",
			serverID,
			"--character-set-server=utf8",
			"--sync_binlog=0",
			"--default-time-zone=+00:00",
			isRoot ? "--user=root" : "",
			gtidParams
		);

		for ( String s : xtraParams.split(" ") ) {
			pb.command().add(s);
		}

		LOGGER.info("booting onetimeserver: " + StringUtils.join(pb.command(), " "));
		Process p = pb.start();
		BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));

		p.waitFor();

		final BufferedReader errReader = new BufferedReader(new InputStreamReader(p.getErrorStream()));

		new Thread() {
			@Override
			public void run() {
				while (true) {
					String l = null;
					try {
						l = errReader.readLine();
					} catch ( IOException e) {};

					if (l == null)
						break;
					System.err.println(l);
				}
			}
		}.start();

		String json = reader.readLine();
		String outputFile = null;
		try {
			ObjectMapper mapper = new ObjectMapper();
			Map<String, Object> output = mapper.readValue(json, MAP_STRING_OBJECT_REF);
			this.port = (int) output.get("port");
			this.serverPid = (int) output.get("server_pid");
			this.path = (String) output.get("mysql_path");
			outputFile = (String) output.get("output");
		} catch ( Exception e ) {
			LOGGER.error("got exception while parsing " + json, e);
			throw(e);
		}


		resetConnection();
		this.connection.createStatement().executeUpdate("CREATE USER 'maxwell'@'127.0.0.1' IDENTIFIED BY 'maxwell'");
		this.connection.createStatement().executeUpdate("GRANT REPLICATION SLAVE on *.* to 'maxwell'@'127.0.0.1'");
		this.connection.createStatement().executeUpdate("GRANT ALL on *.* to 'maxwell'@'127.0.0.1'");
		this.connection.createStatement().executeUpdate("CREATE DATABASE if not exists test");
		LOGGER.info("booted at port " + this.port + ", outputting to file " + outputFile);
	}

	public void setupSlave(int masterPort) throws SQLException {
		Connection master = DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + masterPort + "/mysql?useSSL=false", "root", "");
		ResultSet rs = master.createStatement().executeQuery("show master status");
		if ( !rs.next() )
			throw new RuntimeException("could not get master status");

		String createUserSQL;
		if ( getVersion().atLeast(5, 7) ) {
			createUserSQL = "create user 'maxwell_repl'@'127.0.0.1' identified with 'mysql_native_password' by 'maxwell'";
		} else {
			createUserSQL = "create user 'maxwell_repl'@'127.0.0.1' identified by 'maxwell'";
		}
		master.createStatement().execute(createUserSQL);
		master.createStatement().execute("grant replication slave, replication client on *.* to 'maxwell_repl'@'127.0.0.1'");

		String file = rs.getString("File");
		Long position = rs.getLong("Position");

		String changeSQL = String.format(
			"CHANGE MASTER to master_host = '127.0.0.1', master_user='maxwell_repl', master_password='maxwell', "
			+ "master_log_file = '%s', master_log_pos = %d, master_port = %d",
			file, position, masterPort
		);
		LOGGER.info("starting up slave: " + changeSQL);
		getConnection().createStatement().execute(changeSQL);
		getConnection().createStatement().execute("START SLAVE");

		rs.close();
	}

	public void dumpQuery(String query) throws Exception {
		ResultSet rs = getConnection().createStatement().executeQuery(query);
		rs.next();
		for ( int i = 1 ; i <= rs.getMetaData().getColumnCount() ; i++) {
			LOGGER.info("{}: {}", rs.getMetaData().getColumnName(i), rs.getObject(i));
		}

	}

	public void boot() throws Exception {
		boot(null);
	}

	public void resetConnection() throws SQLException {
		this.connection = getNewConnection();
	}

	public Connection getNewConnection() throws SQLException {
		return DriverManager.getConnection("jdbc:mysql://127.0.0.1:" + port + "/mysql?zeroDateTimeBehavior=convertToNull&useSSL=false", "root", "");
	}

	public Connection getConnection() {
		return connection;
	}

	public Connection getConnection(String defaultDB) throws SQLException {
		Connection conn = getNewConnection();
		conn.setCatalog(defaultDB);
		return conn;
	}

	public void execute(String query) throws SQLException {
		Statement s = getConnection().createStatement();
		s.executeUpdate(query);
		s.close();
	}

	private Connection cachedCX;
	public void executeCached(String query) throws SQLException {
		if ( cachedCX == null )
			cachedCX = getConnection();

		Statement s = cachedCX.createStatement();
		s.executeUpdate(query);
		s.close();
	}

	public void executeList(List<String> queries) throws SQLException {
		for (String q: queries) {
			if ( q.matches("^\\s*$") )
				continue;

			execute(q);
		}
	}

	public void executeList(String[] schemaSQL) throws SQLException {
		executeList(Arrays.asList(schemaSQL));
	}

	public void executeQuery(String sql) throws SQLException {
		getConnection().createStatement().executeUpdate(sql);
	}

	public ResultSet query(String sql) throws SQLException {
		return getConnection().createStatement().executeQuery(sql);
	}

	public int getPort() {
		return port;
	}

	public void shutDown() {
		try {
			Runtime.getRuntime().exec("kill " + this.serverPid);
		} catch ( IOException e ) {}
	}

	private static String getVersionString() {
		String mysqlVersion = System.getenv("MYSQL_VERSION");
		return mysqlVersion == null ? "5.6" : mysqlVersion;
	}

	public static MysqlVersion getVersion() {
		String[] parts = getVersionString().split("\\.");
		return new MysqlVersion(Integer.valueOf(parts[0]), Integer.valueOf(parts[1]));
	}

	public boolean supportsZeroDates() {
		// https://dev.mysql.com/doc/refman/5.7/en/sql-mode.html#sqlmode_no_zero_date
		return !getVersion().atLeast(VERSION_5_7);
	}

	public void waitForSlaveToBeCurrent(MysqlIsolatedServer master) throws Exception {
		ResultSet ms = master.query("show master status");
		ms.next();
		String masterFile = ms.getString("File");
		Long masterPos = ms.getLong("Position");
		ms.close();

		while ( true ) {
			ResultSet rs = query("show slave status");
			rs.next();
			if ( rs.getString("Relay_Master_Log_File").equals(masterFile) &&
				rs.getLong("Exec_Master_Log_Pos") >= masterPos )
				return;

			Thread.sleep(200);
		}
	}
}
