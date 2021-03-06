/*
 * Copyright 2017 Confluent Inc.
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

package io.confluent.ksql.codegen;

import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Schema.Type;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IExpressionEvaluator;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.confluent.ksql.function.FunctionRegistry;
import io.confluent.ksql.function.KsqlFunction;
import io.confluent.ksql.function.UdfFactory;
import io.confluent.ksql.function.udf.Kudf;
import io.confluent.ksql.parser.tree.ArithmeticBinaryExpression;
import io.confluent.ksql.parser.tree.AstVisitor;
import io.confluent.ksql.parser.tree.Cast;
import io.confluent.ksql.parser.tree.ComparisonExpression;
import io.confluent.ksql.parser.tree.DereferenceExpression;
import io.confluent.ksql.parser.tree.Expression;
import io.confluent.ksql.parser.tree.FunctionCall;
import io.confluent.ksql.parser.tree.IsNotNullPredicate;
import io.confluent.ksql.parser.tree.IsNullPredicate;
import io.confluent.ksql.parser.tree.LikePredicate;
import io.confluent.ksql.parser.tree.LogicalBinaryExpression;
import io.confluent.ksql.parser.tree.NotExpression;
import io.confluent.ksql.parser.tree.QualifiedNameReference;
import io.confluent.ksql.parser.tree.SubscriptExpression;
import io.confluent.ksql.util.ExpressionMetadata;
import io.confluent.ksql.util.ExpressionTypeManager;
import io.confluent.ksql.util.SchemaUtil;

public class CodeGenRunner {

  private final Schema schema;
  private final FunctionRegistry functionRegistry;
  private final ExpressionTypeManager expressionTypeManager;

  public CodeGenRunner(Schema schema, FunctionRegistry functionRegistry) {
    this.functionRegistry = functionRegistry;
    this.schema = schema;
    this.expressionTypeManager = new ExpressionTypeManager(schema, functionRegistry);
  }

  public Set<ParameterType> getParameterInfo(final Expression expression) {
    Visitor visitor = new Visitor(schema, functionRegistry, expressionTypeManager);
    visitor.process(expression, null);
    return visitor.parameters;
  }

  public ExpressionMetadata buildCodeGenFromParseTree(
      final Expression expression
  ) throws Exception {

    final Set<ParameterType> parameters = getParameterInfo(expression);

    final String[] parameterNames = new String[parameters.size()];
    final Class[] parameterTypes = new Class[parameters.size()];
    final int[] columnIndexes = new int[parameters.size()];
    final Kudf[] kudfObjects = new Kudf[parameters.size()];

    int index = 0;
    for (final ParameterType param : parameters) {
      parameterNames[index] = param.name;
      parameterTypes[index] = param.type;
      columnIndexes[index] = SchemaUtil.getFieldIndexByName(schema, param.name);
      kudfObjects[index] = param.getKudf();
      index++;
    }

    String javaCode = new SqlToJavaVisitor(schema, functionRegistry).process(expression);

    IExpressionEvaluator ee =
        CompilerFactoryFactory.getDefaultCompilerFactory().newExpressionEvaluator();

    ee.setParameters(parameterNames, parameterTypes);

    Schema expressionType = expressionTypeManager.getExpressionSchema(expression);

    ee.setExpressionType(SchemaUtil.getJavaType(expressionType));

    ee.cook(javaCode);

    return new ExpressionMetadata(ee, columnIndexes, kudfObjects, expressionType);
  }

  private static class Visitor extends AstVisitor<Object, Object> {

    private final Schema schema;
    private final Set<ParameterType> parameters;
    private final FunctionRegistry functionRegistry;
    private final ExpressionTypeManager expressionTypeManager;

    private int functionCounter = 0;

    Visitor(
        final Schema schema,
        final FunctionRegistry functionRegistry,
        final ExpressionTypeManager expressionTypeManager) {
      this.schema = schema;
      this.parameters = new HashSet<>();
      this.functionRegistry = functionRegistry;
      this.expressionTypeManager = expressionTypeManager;
    }

    private void addParameter(Optional<Field> schemaField) {
      schemaField.ifPresent(f -> parameters.add(new ParameterType(
          SchemaUtil.getJavaType(f.schema()),
          f.name().replace(".", "_"))));
    }

    protected Object visitLikePredicate(LikePredicate node, Object context) {
      process(node.getValue(), null);
      return null;
    }

    protected Object visitFunctionCall(FunctionCall node, Object context) {
      final int functionNumber = functionCounter++;
      final List<Type> argumentTypes = new ArrayList<>();
      final String functionName = node.getName().getSuffix();
      for (Expression argExpr : node.getArguments()) {
        process(argExpr, null);
        argumentTypes.add(expressionTypeManager.getExpressionType(argExpr));
      }

      final UdfFactory holder = functionRegistry.getUdfFactory(functionName);
      final KsqlFunction function = holder.getFunction(argumentTypes);
      parameters.add(new ParameterType(function,
          node.getName().getSuffix() + "_" + functionNumber));
      return null;
    }


    protected Object visitArithmeticBinary(ArithmeticBinaryExpression node, Object context) {
      process(node.getLeft(), null);
      process(node.getRight(), null);
      return null;
    }

    protected Object visitIsNotNullPredicate(IsNotNullPredicate node, Object context) {
      return process(node.getValue(), context);
    }

    protected Object visitIsNullPredicate(IsNullPredicate node, Object context) {
      return process(node.getValue(), context);
    }

    protected Object visitLogicalBinaryExpression(LogicalBinaryExpression node, Object context) {
      process(node.getLeft(), null);
      process(node.getRight(), null);
      return null;
    }

    @Override
    protected Object visitComparisonExpression(ComparisonExpression node, Object context) {
      process(node.getLeft(), null);
      process(node.getRight(), null);
      return null;
    }

    @Override
    protected Object visitNotExpression(NotExpression node, Object context) {
      return process(node.getValue(), null);
    }

    @Override
    protected Object visitDereferenceExpression(DereferenceExpression node, Object context) {
      Optional<Field> schemaField = SchemaUtil.getFieldByName(schema, node.toString());
      if (!schemaField.isPresent()) {
        throw new RuntimeException(
            "Cannot find the select field in the available fields: " + node.toString());
      }
      addParameter(schemaField);
      return null;
    }

    @Override
    protected Object visitCast(Cast node, Object context) {
      process(node.getExpression(), context);
      return null;
    }

    @Override
    protected Object visitSubscriptExpression(SubscriptExpression node, Object context) {
      String arrayBaseName = node.getBase().toString();
      Optional<Field> schemaField = SchemaUtil.getFieldByName(schema, arrayBaseName);
      if (!schemaField.isPresent()) {
        throw new RuntimeException(
            "Cannot find the select field in the available fields: " + arrayBaseName);
      }
      addParameter(schemaField);
      process(node.getIndex(), context);
      return null;
    }

    @Override
    protected Object visitQualifiedNameReference(QualifiedNameReference node, Object context) {
      Optional<Field> schemaField = SchemaUtil.getFieldByName(schema, node.getName().getSuffix());
      if (!schemaField.isPresent()) {
        throw new RuntimeException(
            "Cannot find the select field in the available fields: " + node.getName().getSuffix());
      }
      addParameter(schemaField);
      return null;
    }

  }

  public static class ParameterType {
    private final Class type;
    @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
    private final Optional<KsqlFunction> function;
    private final String name;

    ParameterType(final Class type, final String name) {
      this(null, Objects.requireNonNull(type, "type can't be null"), name);
    }

    ParameterType(final KsqlFunction function, final String name) {
      this(Objects.requireNonNull(function, "function can't be null"),
          function.getKudfClass(),
          name);
    }

    private ParameterType(final KsqlFunction function, final Class type, final String name) {
      this.function = Optional.ofNullable(function);
      this.type = type;
      this.name = Objects.requireNonNull(name);
    }

    public Class getType() {
      return type;
    }

    public String getName() {
      return name;
    }

    public Kudf getKudf() {
      return function.map(KsqlFunction::newInstance).orElse(null);
    }

    @Override
    public boolean equals(final Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      final ParameterType that = (ParameterType) o;
      return Objects.equals(type, that.type)
          && Objects.equals(function, that.function)
          && Objects.equals(name, that.name);
    }

    @Override
    public int hashCode() {
      return Objects.hash(type, function, name);
    }
  }
}
