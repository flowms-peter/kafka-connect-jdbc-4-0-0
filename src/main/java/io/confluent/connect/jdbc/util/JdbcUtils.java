/**
 * Copyright 2015 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.connect.jdbc.util;

import org.apache.kafka.connect.errors.ConnectException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utilties for interacting with a JDBC database.
 */
public class JdbcUtils {

  private static final Logger log = LoggerFactory.getLogger(JdbcUtils.class);

  /**
   * The default table types to include when listing tables if none are specified. Valid values
   * are those specified by the @{java.sql.DatabaseMetaData#getTables} method's TABLE_TYPE column.
   * The default only includes standard, user-defined tables.
   */
  public static final Set<String> DEFAULT_TABLE_TYPES = Collections.unmodifiableSet(
      new HashSet<>(Arrays.asList("TABLE"))
  );

  private static final int GET_TABLES_TYPE_COLUMN = 4;
  private static final int GET_TABLES_NAME_COLUMN = 3;

  private static final int GET_COLUMNS_COLUMN_NAME = 4;
  private static final int GET_COLUMNS_IS_NULLABLE = 18;
  private static final int GET_COLUMNS_IS_AUTOINCREMENT = 23;


  /**
   * Get a list of tables in the database. This uses the default filters, which only include
   * user-defined tables.
   * @param conn database connection
   * @return a list of tables; never null
   * @throws SQLException if there is an error with the database connection
   */
  public static List<String> getTables(Connection conn, String schemaPattern) throws SQLException {
    return getTables(conn, schemaPattern, DEFAULT_TABLE_TYPES);
  }

  /**
   * Get a list of table names in the database.
   * @param conn database connection
   * @param types a set of table types that should be included in the results; may not be null
   *              but may be empty if the tables should be returned regardless of their type
   * @return a list of tables; never null
   * @throws SQLException if there is an error with the database connection
   */
  public static List<String> getTables(
      Connection conn,
      String schemaPattern,
      Set<String> types
  ) throws SQLException {
    DatabaseMetaData metadata = conn.getMetaData();
    String[] tableTypes = types.isEmpty() ? null : getActualTableTypes(metadata, types);

    try (ResultSet rs = metadata.getTables(null, schemaPattern, "%", tableTypes)) {
      List<String> tableNames = new ArrayList<>();
      while (rs.next()) {
        String colName = rs.getString(GET_TABLES_NAME_COLUMN);
        // SQLite JDBC driver does not correctly mark these as system tables
        if (metadata.getDatabaseProductName().equals("SQLite") && colName.startsWith("sqlite_")) {
          continue;
        }

        tableNames.add(colName);
      }
      return tableNames;
    }
  }

  /**
   * Find the available table types that are returned by the JDBC driver that case insensitively
   * match the specified types.
   *
   * @param metadata the database metadata; may not be null but may be empty if no table types
   * @param types the case-independent table types
   * @return the array of table types take directly from the list of available types returned by
   *         the JDBC driver; never null
   * @throws SQLException if there is an error with the database connection
   */
  protected static String[] getActualTableTypes(
      DatabaseMetaData metadata,
      Set<String> types
  ) throws SQLException {
    // Compute the uppercase form of the desired types ...
    Set<String> uppercaseTypes = new HashSet<>();
    for (String type : types) {
      if (type != null) {
        uppercaseTypes.add(type.toUpperCase());
      }
    }
    // Now find out the available table types ...
    Set<String> matchingTableTypes = new HashSet<>();
    try (ResultSet rs = metadata.getTableTypes()) {
      while (rs.next()) {
        String tableType = rs.getString(1);
        if (tableType != null && uppercaseTypes.contains(tableType.toUpperCase())) {
          matchingTableTypes.add(tableType);
        }
      }
    }
    return matchingTableTypes.toArray(new String[matchingTableTypes.size()]);
  }

  /**
   * Look up the autoincrement column for the specified table.
   * @param conn database connection
   * @param table the table to
   * @return the name of the column that is an autoincrement column, or null if there is no
   *         autoincrement column or more than one exists
   * @throws SQLException if there is an error with the database connection
   */
  public static String getAutoincrementColumn(
      Connection conn,
      String schemaPattern,
      String table
  ) throws SQLException {
    String result = null;
    int matches = 0;

    try (ResultSet rs = conn.getMetaData().getColumns(null, schemaPattern, table, "%")) {
      // Some database drivers (SQLite) don't include all the columns
      if (rs.getMetaData().getColumnCount() >= GET_COLUMNS_IS_AUTOINCREMENT) {
        while (rs.next()) {
          if (rs.getString(GET_COLUMNS_IS_AUTOINCREMENT).equals("YES")) {
            result = rs.getString(GET_COLUMNS_COLUMN_NAME);
            matches++;
          }
        }
        return (matches == 1 ? result : null);
      }
    }

    // Fallback approach is to query for a single row. This unfortunately does not work with any
    // empty table
    log.trace("Falling back to SELECT detection of auto-increment column for {}:{}", conn, table);
    try (Statement stmt = conn.createStatement()) {
      String quoteString = getIdentifierQuoteString(conn);
      ResultSet rs = stmt.executeQuery(
          "SELECT * FROM " + quoteString + table + quoteString + " LIMIT 1"
      );
      ResultSetMetaData rsmd = rs.getMetaData();
      for (int i = 1; i < rsmd.getColumnCount(); i++) {
        if (rsmd.isAutoIncrement(i)) {
          result = rsmd.getColumnName(i);
          matches++;
        }
      }
    }
    return (matches == 1 ? result : null);
  }

  public static boolean isColumnNullable(
      Connection conn,
      String schemaPattern,
      String table,
      String column
  ) throws SQLException {
    try (ResultSet rs = conn.getMetaData().getColumns(null, schemaPattern, table, column)) {
      if (rs.getMetaData().getColumnCount() > GET_COLUMNS_IS_NULLABLE) {
        // Should only be one match
        if (!rs.next()) {
          return false;
        }
        return rs.getString(GET_COLUMNS_IS_NULLABLE).equals("YES");
      }
    }

    return false;
  }

  /**
   * Get the string used for quoting identifiers in this database's SQL dialect.
   * @param connection the database connection
   * @return the quote string
   * @throws SQLException if there is an error with the database connection
   */
  public static String getIdentifierQuoteString(Connection connection) throws SQLException {
    String quoteString = connection.getMetaData().getIdentifierQuoteString();
    quoteString = quoteString == null ? "" : quoteString;
    return quoteString;
  }

  /**
   * Quote the given string.
   * @param orig the string to quote
   * @param quote the quote character
   * @return the quoted string
   */
  public static String quoteString(String orig, String quote) {
    return quote + orig + quote;
  }

  /**
   * Return current time at the database
   * @param conn database connection
   * @param cal calendar
   * @return the current time at the database
   */
  public static Timestamp getCurrentTimeOnDB(
      Connection conn,
      Calendar cal
  ) throws SQLException, ConnectException {
    String query;

    // This is ugly, but to run a function, everyone does 'select function()'
    // except Oracle that does 'select function() from dual'
    // and Derby uses either the dummy table SYSIBM.SYSDUMMY1  or values expression (I chose to
    // use values)
    String dbProduct = conn.getMetaData().getDatabaseProductName();
    if ("Oracle".equals(dbProduct)) {
      query = "select CURRENT_TIMESTAMP from dual";
    } else if ("Apache Derby".equals(dbProduct)
        || (dbProduct != null && dbProduct.startsWith("DB2"))) {
      query = "values(CURRENT_TIMESTAMP)";
    } else if ("PostgreSQL".equals(dbProduct)) {
      query = "select localtimestamp;";
    } else {
      query = "select CURRENT_TIMESTAMP;";
    }

    Statement stmt = null;
    conn.commit();
    //try (Statement stmt = conn.createStatement()) {
    try {
      stmt = conn.createStatement();
      log.debug("executing query " + query + " to get current time from database");
      ResultSet rs = stmt.executeQuery(query);
      if (rs.next()) {
        log.debug("executing query " + query + " result " + rs.getTimestamp(1, cal));
        Timestamp ts = rs.getTimestamp(1, cal);
        log.debug("executing query " + query + " result " + ts);
        rs.close();
        stmt.close();
        return ts;
      } else {
        throw new ConnectException(
            "Unable to get current time from DB using query " + query + " on database " + dbProduct
        );
      }
    } catch (SQLException e) {
      log.error(
          "Failed to get current time from DB using query " + query + " on database " + dbProduct,
          e
      );
      throw e;
    }
  }
}

