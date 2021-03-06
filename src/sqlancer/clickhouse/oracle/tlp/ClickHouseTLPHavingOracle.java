package sqlancer.clickhouse.oracle.tlp;

import sqlancer.ComparatorHelper;
import sqlancer.Randomly;
import sqlancer.clickhouse.ClickHouseErrors;
import sqlancer.clickhouse.ClickHouseProvider;
import sqlancer.clickhouse.ClickHouseSchema;
import sqlancer.clickhouse.ClickHouseVisitor;
import sqlancer.clickhouse.ast.ClickHouseColumnReference;
import sqlancer.clickhouse.ast.ClickHouseExpression;
import sqlancer.clickhouse.ast.ClickHouseSelect;
import sqlancer.clickhouse.ast.ClickHouseUnaryPostfixOperation;
import sqlancer.clickhouse.ast.ClickHouseUnaryPrefixOperation;
import sqlancer.clickhouse.gen.ClickHouseCommon;
import sqlancer.clickhouse.gen.ClickHouseExpressionGenerator;

import java.sql.SQLException;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

public class ClickHouseTLPHavingOracle extends ClickHouseTLPBase {

    public ClickHouseTLPHavingOracle(ClickHouseProvider.ClickHouseGlobalState state) {
        super(state);
        ClickHouseErrors.addGroupingErrors(errors);
    }

    @Override
    public void check() throws SQLException {
        ClickHouseSchema s = state.getSchema();
        ClickHouseSchema.ClickHouseTables targetTables = s.getRandomTableNonEmptyTables();
        List<ClickHouseExpression> groupByColumns = Randomly.nonEmptySubset(targetTables.getColumns()).stream()
                .map(c -> new ClickHouseColumnReference(c, null)).collect(Collectors.toList());
        List<ClickHouseSchema.ClickHouseColumn> columns = targetTables.getColumns();
        ClickHouseExpressionGenerator gen = new ClickHouseExpressionGenerator(state).setColumns(columns);
        ClickHouseExpressionGenerator aggrGen = new ClickHouseExpressionGenerator(state).allowAggregates(true)
                .setColumns(columns);
        ClickHouseSelect select = new ClickHouseSelect();
        select.setFetchColumns(aggrGen.generateExpressions(Randomly.smallNumber() + 1));
        List<ClickHouseSchema.ClickHouseTable> tables = targetTables.getTables();
        List<ClickHouseExpression.ClickHouseJoin> joinStatements = gen.getRandomJoinClauses(tables);
        List<ClickHouseExpression> from = ClickHouseCommon.getTableRefs(tables, state.getSchema());
        select.setJoinClauses(joinStatements);
        select.setSelectType(ClickHouseSelect.SelectType.ALL);
        select.setFromTables(from);
        // TODO order by?
        select.setGroupByClause(groupByColumns);
        select.setHavingClause(null);
        String originalQueryString = ClickHouseVisitor.asString(select);

        List<String> resultSet = ComparatorHelper.getResultSetFirstColumnAsString(originalQueryString, errors,
                state.getConnection(), state);

        ClickHouseExpression predicate = aggrGen.getHavingClause();
        select.setHavingClause(predicate);
        String firstQueryString = ClickHouseVisitor.asString(select);
        select.setHavingClause(new ClickHouseUnaryPrefixOperation(predicate,
                ClickHouseUnaryPrefixOperation.ClickHouseUnaryPrefixOperator.NOT));
        String secondQueryString = ClickHouseVisitor.asString(select);
        select.setHavingClause(new ClickHouseUnaryPostfixOperation(predicate,
                ClickHouseUnaryPostfixOperation.ClickHouseUnaryPostfixOperator.IS_NULL, false));
        String thirdQueryString = ClickHouseVisitor.asString(select);
        String combinedString = firstQueryString + " UNION ALL " + secondQueryString + " UNION ALL " + thirdQueryString;
        List<String> secondResultSet = ComparatorHelper.getResultSetFirstColumnAsString(combinedString, errors,
                state.getConnection(), state);
        if (state.getOptions().logEachSelect()) {
            state.getLogger().writeCurrent(originalQueryString);
            state.getLogger().writeCurrent(combinedString);
        }
        if (new HashSet<>(resultSet).size() != new HashSet<>(secondResultSet).size()) {
            throw new AssertionError(originalQueryString + ";\n" + combinedString + ";");
        }
    }
}
