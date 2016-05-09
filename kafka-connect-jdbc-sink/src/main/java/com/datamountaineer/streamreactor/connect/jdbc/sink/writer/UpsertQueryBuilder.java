package com.datamountaineer.streamreactor.connect.jdbc.sink.writer;

import com.datamountaineer.streamreactor.connect.jdbc.sink.writer.dialect.DbDialect;

import java.util.List;

/**
 * Builds an UPSERT sql statement.
 */
public class UpsertQueryBuilder implements QueryBuilder {
    private final DbDialect dbDialect;

    public UpsertQueryBuilder(DbDialect dialect) {
        this.dbDialect = dialect;
    }

    /**
     * Builds the sql statement for an upsert
     *
     * @param table         - The target table
     * @param nonKeyColumns - A list of columns in the target table which are not part of the primary key
     * @param keyColumns    - A list of columns in the target table which make the primary key
     * @return
     */
    @Override
    public String build(String table, List<String> nonKeyColumns, List<String> keyColumns) {
        return dbDialect.getUpsertQuery(table, nonKeyColumns, keyColumns);
    }


    public DbDialect getDbDialect() {
        return dbDialect;
    }
}
