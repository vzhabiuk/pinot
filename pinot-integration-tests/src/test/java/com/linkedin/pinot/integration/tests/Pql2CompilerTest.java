package com.linkedin.pinot.integration.tests;

import com.linkedin.pinot.common.client.request.RequestConverter;
import com.linkedin.pinot.common.request.BrokerRequest;
import com.linkedin.pinot.common.utils.EqualityUtils;
import com.linkedin.pinot.pql.parsers.PQLCompiler;
import com.linkedin.pinot.pql.parsers.Pql2Compiler;
import java.io.File;
import java.io.FileReader;
import java.io.LineNumberReader;
import java.util.Collections;
import java.util.HashMap;
import org.json.JSONObject;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.linkedin.pinot.common.request.AggregationInfo;
import com.linkedin.pinot.common.request.FilterOperator;
import com.linkedin.pinot.common.request.FilterQuery;
import com.linkedin.pinot.common.request.FilterQueryMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * TODO Document me!
 *
 * @author jfim
 */
public class Pql2CompilerTest {
  private void testQuery(PQLCompiler pql1Compiler, Pql2Compiler pql2Compiler, String pql) {
    try {
      System.out.println(pql);
      // Skip ones that don't compile with Pinot 1
      JSONObject jsonObject;
      try {
        jsonObject = pql1Compiler.compile(pql);
      } catch (Exception e) {
        return;
      }

      BrokerRequest pqlBrokerRequest = RequestConverter.fromJSON(jsonObject);
      BrokerRequest pql2BrokerRequest = pql2Compiler.compileToBrokerRequest(pql);
      Assert.assertTrue(brokerRequestsAreEquivalent(pqlBrokerRequest, pql2BrokerRequest),
          "Requests are not equivalent\npql2: " + pql2BrokerRequest + "\npql: " + pqlBrokerRequest + "\nquery:" + pql);
    } catch (Exception e) {
      Assert.fail("Caught exception compiling " + pql, e);
    }
  }
  
  @Test
  public void testHardcodedQueries() {
    PQLCompiler pql1Compiler = new PQLCompiler(new HashMap<String, String[]>());
    Pql2Compiler pql2Compiler = new Pql2Compiler();

    testQuery(pql1Compiler, pql2Compiler, "select count(*) from foo where x not in (1,2,3)");
  }
  
  @Test
  public void testGeneratedQueries() {
    File avroFile = new File("pinot-integration-tests/src/test/resources/On_Time_On_Time_Performance_2014_1.avro");
    QueryGenerator qg = new QueryGenerator(Collections.singletonList(avroFile), "whatever", "whatever");

    PQLCompiler pql1Compiler = new PQLCompiler(new HashMap<String, String[]>());
    Pql2Compiler pql2Compiler = new Pql2Compiler();

    for (int i = 1; i <= 1000000; i++) {
      String pql = qg.generateQuery().generatePql();
      testQuery(pql1Compiler, pql2Compiler, pql);
    }
  }

  @Test
  public void testLotsOfBqls() throws Exception {
    PQLCompiler pql1Compiler = new PQLCompiler(new HashMap<String, String[]>());
    Pql2Compiler pql2Compiler = new Pql2Compiler();
    LineNumberReader reader = new LineNumberReader(new FileReader("pinot-integration-tests/src/test/resources/lots-of-bqls.log"));

    String line = reader.readLine();
    while (line != null) {
      if (!line.contains("com.senseidb.ba")) {
        testQuery(pql1Compiler, pql2Compiler, line);
      }
      line = reader.readLine();
    }

    reader.close();
  }

  private boolean brokerRequestsAreEquivalent(BrokerRequest left, BrokerRequest right) {
    boolean basicFieldsAreEquivalent = EqualityUtils.isEqual(left.getQueryType(), right.getQueryType()) &&
        EqualityUtils.isEqual(left.getQuerySource(), right.getQuerySource()) &&
        EqualityUtils.isEqual(left.getTimeInterval(), right.getTimeInterval()) &&
        EqualityUtils.isEqual(left.getDuration(), right.getDuration()) &&
        EqualityUtils.isEqual(left.getSelections(), right.getSelections()) &&
        EqualityUtils.isEqual(left.getBucketHashKey(), right.getBucketHashKey());

    boolean aggregationsAreEquivalent = true;

    List<AggregationInfo> leftAggregationsInfo = left.getAggregationsInfo();
    List<AggregationInfo> rightAggregationsInfo = right.getAggregationsInfo();
    if (!EqualityUtils.isEqual(leftAggregationsInfo, rightAggregationsInfo)) {
      if (leftAggregationsInfo == null || rightAggregationsInfo == null ||
          leftAggregationsInfo.size() != rightAggregationsInfo.size()) {
        aggregationsAreEquivalent = false;
      } else {
        int aggregationsInfoCount = leftAggregationsInfo.size();
        for (int i = 0; i < aggregationsInfoCount; i++) {
          AggregationInfo leftInfo = leftAggregationsInfo.get(i);
          AggregationInfo rightInfo = rightAggregationsInfo.get(i);

          // Check if the aggregationsInfo are the same or they're the count function
          if (EqualityUtils.isEqual(leftInfo, rightInfo)) {
            continue;
          } else {
            if ("count".equalsIgnoreCase(rightInfo.getAggregationType()) &&
                "count".equalsIgnoreCase(leftInfo.getAggregationType())
                ) {
              continue;
            } else {
              aggregationsAreEquivalent = false;
              break;
            }
          }
        }
      }
    }

    // Group by clauses might not be in the same order
    boolean groupByClauseIsEquivalent = EqualityUtils.isEqual(left.getGroupBy(), right.getGroupBy());

    if (!groupByClauseIsEquivalent) {
      groupByClauseIsEquivalent =
          (EqualityUtils.isEqualIgnoringOrder(left.getGroupBy().getColumns(), right.getGroupBy().getColumns()) &&
              EqualityUtils.isEqual(left.getGroupBy().getTopN(), right.getGroupBy().getTopN()));
    }

    boolean filtersAreEquivalent = EqualityUtils.isEqual(left.isSetFilterQuery(), right.isSetFilterQuery());

    if (left.isSetFilterQuery()) {
      int leftRootId = left.getFilterQuery().getId();
      int rightRootId = right.getFilterQuery().getId();
      // The Pql 1 compiler merges ranges, the Pql 2 compiler doesn't, so we skip the filter comparison if either side
      // has more than one range filter for the same column
      if (filtersHaveAtMostOneRangeFilterPerColumn(left, right)) {
        filtersAreEquivalent =
            filterQueryIsEquivalent(Collections.singletonList(leftRootId), Collections.singletonList(rightRootId), left.getFilterSubQueryMap(),
                right.getFilterSubQueryMap());
      } else {
        filtersAreEquivalent = true;
      }
    }

    return basicFieldsAreEquivalent && aggregationsAreEquivalent && groupByClauseIsEquivalent && filtersAreEquivalent;
  }

  private boolean filtersHaveAtMostOneRangeFilterPerColumn(BrokerRequest left, BrokerRequest right) {
    Set<String> leftRangeFilterColumns = new HashSet<String>();
    for (FilterQuery filterQuery : left.getFilterSubQueryMap().getFilterQueryMap().values()) {
      if (filterQuery.getOperator() == FilterOperator.RANGE) {
        String column = filterQuery.getColumn();
        if (leftRangeFilterColumns.contains(column)) {
          return false;
        } else {
          leftRangeFilterColumns.add(column);
        }
      }
    }

    Set<String> rightRangeFilterColumns = new HashSet<String>();
    for (FilterQuery filterQuery : right.getFilterSubQueryMap().getFilterQueryMap().values()) {
      if (filterQuery.getOperator() == FilterOperator.RANGE) {
        String column = filterQuery.getColumn();
        if (rightRangeFilterColumns.contains(column)) {
          return false;
        } else {
          rightRangeFilterColumns.add(column);
        }
      }
    }

    return true;
  }

  private boolean filterQueryIsEquivalent(List<Integer> leftIds, List<Integer> rightIds,
      FilterQueryMap leftFilterQueries, FilterQueryMap rightFilterQueries) {
    ArrayList<Integer> leftIdsCopy = new ArrayList<Integer>(leftIds);
    ArrayList<Integer> rightIdsCopy = new ArrayList<Integer>(rightIds);


    if (leftIdsCopy.size() != rightIdsCopy.size()) {
      return false;
    }

    Iterator<Integer> leftIterator = leftIdsCopy.iterator();

    while (leftIterator.hasNext()) {
      Integer leftId = leftIterator.next();
      FilterQuery leftQuery = leftFilterQueries.getFilterQueryMap().get(leftId);

      Iterator<Integer> rightIterator = rightIdsCopy.iterator();
      while (rightIterator.hasNext()) {
        Integer rightId = rightIterator.next();
        FilterQuery rightQuery = rightFilterQueries.getFilterQueryMap().get(rightId);

        boolean operatorsAreEqual = EqualityUtils.isEqual(leftQuery.getOperator(), rightQuery.getOperator());
        boolean columnsAreEqual = EqualityUtils.isEqual(leftQuery.getColumn(), rightQuery.getColumn());
        boolean fieldsAreEqual = columnsAreEqual &&
            operatorsAreEqual &&
            EqualityUtils.isEqual(leftQuery.getValue(), rightQuery.getValue());

        // Compare sets if the op is IN
        if (operatorsAreEqual && columnsAreEqual && leftQuery.getOperator() == FilterOperator.IN) {
          Set<String> leftValues = new HashSet<String>(Arrays.asList(leftQuery.getValue().get(0).split("\t\t")));
          Set<String> rightValues = new HashSet<String>(Arrays.asList(rightQuery.getValue().get(0).split("\t\t")));
          fieldsAreEqual = leftValues.equals(rightValues);
          if (!fieldsAreEqual) {
            System.out.println("in clause not the same?");
            System.out.println("leftValues = " + leftValues);
            System.out.println("rightValues = " + rightValues);
          }
        }

        if (fieldsAreEqual) {
          if (filterQueryIsEquivalent(
              leftQuery.getNestedFilterQueryIds(),
              rightQuery.getNestedFilterQueryIds(),
              leftFilterQueries,
              rightFilterQueries
          )) {
            leftIterator.remove();
            rightIterator.remove();
            break;
          } else {
            return false;
          }
        }
      }
    }

    return leftIdsCopy.isEmpty();
  }
}