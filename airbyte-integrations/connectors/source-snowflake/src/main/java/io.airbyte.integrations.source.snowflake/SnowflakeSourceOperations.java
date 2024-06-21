/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.source.snowflake;

import static io.airbyte.cdk.db.jdbc.DateTimeConverter.putJavaSQLTime;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_COLUMN_NAME;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_COLUMN_TYPE;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_COLUMN_TYPE_NAME;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_SCHEMA_NAME;
import static io.airbyte.cdk.db.jdbc.JdbcConstants.INTERNAL_TABLE_NAME;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airbyte.cdk.db.DataTypeUtils;
import io.airbyte.cdk.db.jdbc.DateTimeConverter;
import io.airbyte.cdk.db.jdbc.JdbcSourceOperations;
import io.airbyte.protocol.models.JsonSchemaType;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.JDBCType;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SnowflakeSourceOperations extends JdbcSourceOperations {

  private static final Logger LOGGER = LoggerFactory.getLogger(SnowflakeSourceOperations.class);

  @Override
  protected void putDouble(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index) {
    try {
      final double value = resultSet.getDouble(index);
      node.put(columnName, value);
    } catch (final SQLException e) {
      node.put(columnName, (Double) null);
    }
  }

  @Override
  public JDBCType getDatabaseFieldType(final JsonNode field) {
    try {
      final String typeName = field.get(INTERNAL_COLUMN_TYPE_NAME).asText().toLowerCase();
      return "TIMESTAMPLTZ".equalsIgnoreCase(typeName)
          ? JDBCType.TIMESTAMP_WITH_TIMEZONE
          : JDBCType.valueOf(field.get(INTERNAL_COLUMN_TYPE).asInt());
    } catch (final IllegalArgumentException ex) {
      LOGGER.warn(String.format("Could not convert column: %s from table: %s.%s with type: %s. Casting to VARCHAR.",
          field.get(INTERNAL_COLUMN_NAME),
          field.get(INTERNAL_SCHEMA_NAME),
          field.get(INTERNAL_TABLE_NAME),
          field.get(INTERNAL_COLUMN_TYPE)));
      return JDBCType.VARCHAR;
    }
  }

  @Override
  protected void putArray(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index) throws SQLException {
    final String jsonArrayStr = resultSet.getString(index);
    try {
      ObjectMapper mapper = new ObjectMapper();
      JsonNode jsonArrayNode = mapper.readTree(jsonArrayStr);
      node.set(columnName, jsonArrayNode);
      LOGGER.info(String.format("jsonArrayNode: %s ", jsonArrayNode));
    } catch (Exception e) {
      LOGGER.info(String.format("error putArray: %s ", e.getMessage()));
      throw new RuntimeException(e);
    }
  }

  @Override
  protected void putBigInt(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index) {
    try {
      final var value = resultSet.getBigDecimal(index);
      node.put(columnName, value);
    } catch (final SQLException e) {
      node.put(columnName, (BigDecimal) null);
    }
  }

  @Override
  protected void setTimestamp(final PreparedStatement preparedStatement, final int parameterIndex, final String value) throws SQLException {
    preparedStatement.setString(parameterIndex, value);
  }

  protected void setTimestampWithTimezone(final PreparedStatement preparedStatement, final int parameterIndex, final String value)
      throws SQLException {
    preparedStatement.setString(parameterIndex, value);
  }

  protected void setTimeWithTimezone(final PreparedStatement preparedStatement, final int parameterIndex, final String value) throws SQLException {
    preparedStatement.setString(parameterIndex, value);
  }

  @Override
  public JsonSchemaType getAirbyteType(final JDBCType jdbcType) {
    LOGGER.info(String.format("Received jdbcType: %s ", jdbcType));
    return switch (jdbcType) {
      case BIT, BOOLEAN -> JsonSchemaType.BOOLEAN;
      case REAL, FLOAT, DOUBLE, NUMERIC, DECIMAL -> JsonSchemaType.NUMBER;
      case TINYINT, SMALLINT, INTEGER, BIGINT -> JsonSchemaType.INTEGER;
      case CHAR, NCHAR, NVARCHAR, VARCHAR, LONGVARCHAR -> JsonSchemaType.STRING;
      case DATE -> JsonSchemaType.STRING_DATE;
      case TIME -> JsonSchemaType.STRING_TIME_WITHOUT_TIMEZONE;
      case TIMESTAMP -> JsonSchemaType.STRING_TIMESTAMP_WITHOUT_TIMEZONE;
      case TIMESTAMP_WITH_TIMEZONE -> JsonSchemaType.STRING_TIMESTAMP_WITH_TIMEZONE;
      case TIME_WITH_TIMEZONE -> JsonSchemaType.STRING_TIME_WITH_TIMEZONE;
      case BLOB, BINARY, VARBINARY, LONGVARBINARY -> JsonSchemaType.STRING_BASE_64;
      case ARRAY -> JsonSchemaType.ARRAY;
      // since column types aren't necessarily meaningful to Airbyte, liberally convert all unrecgonised
      // types to String
      default -> JsonSchemaType.STRING;
    };
  }

  @Override
  public void copyToJsonField(final ResultSet resultSet, final int colIndex, final ObjectNode json) throws SQLException {
    final String columnName = resultSet.getMetaData().getColumnName(colIndex);
    final String columnTypeName = resultSet.getMetaData().getColumnTypeName(colIndex).toLowerCase();
    LOGGER.info(String.format("Received copyToJsonField columnName: %s and typeName %s and colTypeIndex %s", columnName, columnTypeName, resultSet.getMetaData().getColumnType(colIndex)));
    // TIMESTAMPLTZ data type detected as JDBCType.TIMESTAMP which is not correct
    if ("TIMESTAMPLTZ".equalsIgnoreCase(columnTypeName)) {
      putTimestampWithTimezone(json, columnName, resultSet, colIndex);
      return;
    }
    if ("ARRAY".equalsIgnoreCase(columnTypeName)) {
      putArray(json, columnName, resultSet, colIndex);
      return;
    }
    super.copyToJsonField(resultSet, colIndex, json);
  }

  @Override
  protected void setDate(final PreparedStatement preparedStatement, final int parameterIndex, final String value) throws SQLException {
    final LocalDate date = LocalDate.parse(value);
    preparedStatement.setDate(parameterIndex, Date.valueOf(date));
  }

  private static final DateTimeFormatter SNOWFLAKE_TIMESTAMPTZ_FORMATTER = new DateTimeFormatterBuilder()
      .parseCaseInsensitive()
      .append(DateTimeFormatter.ISO_LOCAL_DATE)
      .appendLiteral(' ')
      .append(DateTimeFormatter.ISO_LOCAL_TIME)
      .optionalStart()
      .appendLiteral(' ')
      .append(DateTimeFormatter.ofPattern("XX"))
      .toFormatter();

  @Override
  protected void putTimestampWithTimezone(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index)
      throws SQLException {
    final String timestampAsString = resultSet.getString(index);
    OffsetDateTime timestampWithOffset = OffsetDateTime.parse(timestampAsString, SNOWFLAKE_TIMESTAMPTZ_FORMATTER);
    node.put(columnName, timestampWithOffset.format(DataTypeUtils.TIMESTAMPTZ_FORMATTER));
  }

  @Override
  protected void putTimestamp(final ObjectNode node, final String columnName, final ResultSet resultSet, final int index) throws SQLException {
    final Timestamp timestamp = resultSet.getTimestamp(index);
    node.put(columnName, DateTimeConverter.convertToTimestamp(timestamp));
  }

  @Override
  protected void putTime(final ObjectNode node,
                         final String columnName,
                         final ResultSet resultSet,
                         final int index)
      throws SQLException {
    putJavaSQLTime(node, columnName, resultSet, index);
  }

}
