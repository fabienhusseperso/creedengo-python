/*
 * creedengo - Python language - Provides rules to reduce the environmental footprint of your Python programs
 * Copyright © 2024 Green Code Initiative (https://green-code-initiative.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.greencodeinitiative.creedengo.python.checks;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sonar.check.Rule;
import org.sonar.plugins.python.api.PythonSubscriptionCheck;
import org.sonar.plugins.python.api.SubscriptionContext;
import org.sonar.plugins.python.api.tree.BinaryExpression;
import org.sonar.plugins.python.api.tree.ElseClause;
import org.sonar.plugins.python.api.tree.Expression;
import org.sonar.plugins.python.api.tree.FunctionDef;
import org.sonar.plugins.python.api.tree.IfStatement;
import org.sonar.plugins.python.api.tree.Statement;
import org.sonar.plugins.python.api.tree.Tree;
import org.sonarsource.analyzer.commons.annotations.DeprecatedRuleKey;

import static org.sonar.plugins.python.api.tree.Tree.Kind.*;

/**
 * FUNCTIONAL DESCRIPTION : please see ASCIIDOC description file of this rule (inside `creedengo-rules-spcifications`)
 * TECHNICAL CHOICES :
 * - Kind.IF_STATEMENT, Kind.ELSE_STATEMENT, Kind.ELSEIF_STATEMENT not used because it isn't possible
 * to keep parent references to check later if variables already used or not in parent tree
 * - only one way to keep parent history : manually go throw the all tree and thus, start at method declaration
 * - an "ELSE" statement is considered as a second IF statement using the same variables used on previous
 * - IF and ELSEIF statements are considered as an IF statement
 */
@Rule(key = "GCI2")
@DeprecatedRuleKey(repositoryKey = "ecocode-python", ruleKey = "EC2")
public class AvoidMultipleIfElseStatementCheck extends PythonSubscriptionCheck {

    public static final String ERROR_MESSAGE = "Use a match-case statement instead of multiple if-else if possible";

    // data structure for following usage of variable inside all the AST tree
    private VariablesPerLevelDataStructure variablesStruct = new VariablesPerLevelDataStructure();

    // only visit each method to keep data of all conditional tree
    // with IF, ELSE or ELSEIF statements, we can't keep all data of conditional tree
    @Override
    public void initialize(Context context) {
        context.registerSyntaxNodeConsumer(FUNCDEF, this::visitFuncDef);
    }

    private void visitFuncDef(SubscriptionContext ctx) {
        visitNode(ctx, ctx.syntaxNode());
    }

    public void visitNode(SubscriptionContext context, Tree pTree) {

        FunctionDef method = (FunctionDef)pTree;

        // reinit data structure before each method analysis
        variablesStruct = new VariablesPerLevelDataStructure();

        // starting visit
        visitNodeContent(context, method.body().statements(), 0);

    }

    /**
     * Visit all content of a node for one level (with its statements list)
     *
     * @param pLstStatements statements list of current node
     * @param pLevel level of current node
     */
    private void visitNodeContent(SubscriptionContext context, List<Statement> pLstStatements, int pLevel) {
        if (pLstStatements == null || pLstStatements.isEmpty()) {
            return;
        }

        for (Statement statement : pLstStatements) {
            if (statement.is(IF_STMT)) {
                visitIfNode(context, (IfStatement)statement, pLevel);
            }
        }
    }

    /**
     * Visit an IF type node
     * @param pIfTree the current node (Tree type)
     * @param pLevel the level of node
     */
    private void visitIfNode(SubscriptionContext context, IfStatement pIfTree, int pLevel) {

        if (pIfTree == null) return;

        // init current if structure with cleaning child levels
        variablesStruct.reinitVariableUsageForLevel(pLevel + 1);
        // init current if structure with cleaning for ELSE process checking
        variablesStruct.reinitVariableUsageForLevelForCurrentIfStruct(pLevel);

        // analyze condition variables and raise error if needed
        computeIfVariables(context, pIfTree, pLevel);

        // visit the content of if block
        visitNodeContent(context, pIfTree.body().statements(), pLevel + 1);

        // analyze ELSEIF clauses
        if (pIfTree.elifBranches() != null && !pIfTree.elifBranches().isEmpty()) {
            for (IfStatement elseifClause : pIfTree.elifBranches()) {
                visitElseIfNode(context, elseifClause, pLevel);
            }
        }

        // analyze ELSE clause
        visitElseNode(context, pIfTree.elseBranch(), pLevel);

    }

    /**
     * Analyze and compute variables usage for IF AST structure
     * @param pIfTree IF node
     * @param pLevel the level of IF node
     */
    private void computeIfVariables(SubscriptionContext context, IfStatement pIfTree, int pLevel) {

        if (pIfTree.condition() == null) return;

        // analysing content of conditions of IF node
        Expression expr = pIfTree.condition();
        if (expr instanceof BinaryExpression) {
            computeConditionVariables(context, (BinaryExpression) expr, pLevel);
        }

    }

    /**
     * Analyze and compute variables usage for Expression structure
     * @param pBinExprTree binary expression to analyze
     * @param pLevel The level of binary expression
     */
    private void computeConditionVariables(SubscriptionContext context, BinaryExpression pBinExprTree, int pLevel) {

        // if multiple conditions, continue with each part of complex expression
        if (pBinExprTree.is(AND) || pBinExprTree.is(OR)) {
            if (pBinExprTree.leftOperand() instanceof BinaryExpression) {
                computeConditionVariables(context, (BinaryExpression) pBinExprTree.leftOperand(), pLevel);
            }
            if (pBinExprTree.rightOperand() instanceof BinaryExpression) {
                computeConditionVariables(context, (BinaryExpression) pBinExprTree.rightOperand(), pLevel);
            }
        } else if (pBinExprTree.is(COMPARISON)) {

            // continue to analyze with variables if some key-words are found
            if (pBinExprTree.leftOperand().is(NAME)) {
                computeVariables(context, pBinExprTree.leftOperand(), pLevel);
            }
            if (pBinExprTree.rightOperand().is(NAME)) {
                computeVariables(context, pBinExprTree.rightOperand(), pLevel);
            }
        }
    }

    /**
     * Analyze and compute variables usage for Variable AST structure
     * @param pVarIdTree The Variable AST structure
     * @param pLevel the level of structure
     */
    private void computeVariables(SubscriptionContext context, Expression pVarIdTree, int pLevel) {
        // increment the variable counter to list of all variables
        int nbUsed = variablesStruct.incrementVariableUsageForLevel(pVarIdTree.firstToken().value(), pLevel);

        // increment variable counter to list of variables already declared for current if or elseif struture
        variablesStruct.incrementVariableUsageForLevelForCurrentIfStruct(pVarIdTree.firstToken().value(), pLevel);

        // raise an error if maximum
        if (nbUsed > 2) {
            context.addIssue(pVarIdTree.firstToken(), ERROR_MESSAGE);
        }
    }

    /**
     * Analyze and compute variables usage for ELSEIF AST structure
     * @param pElseIfTree ELSEIF node
     * @param pLevel the level of ELSEIF node
     */
    private void visitElseIfNode(SubscriptionContext context, IfStatement pElseIfTree, int pLevel) {

        if (pElseIfTree == null) { return; }

        // init current if structure with cleaning child levels
        variablesStruct.reinitVariableUsageForLevel(pLevel + 1);

        // init current if structure with cleaning for else verification
        variablesStruct.reinitVariableUsageForLevelForCurrentIfStruct(pLevel);

        // analyze variables and raise error if needed
        computeElseIfVariables(context, pElseIfTree, pLevel);

        // go to next child level
        visitNodeContent(context, pElseIfTree.body().statements(), pLevel + 1);
    }

    /**
     * Analyze and compute variables usage for ELSEIF AST structure
     * @param pElseIfTree ELSEIF node
     * @param pLevel the level of ELSEIF node
     */
    private void computeElseIfVariables(SubscriptionContext context, IfStatement pElseIfTree, int pLevel) {

        if (pElseIfTree.condition() == null) return;

        Expression expr = pElseIfTree.condition();
        if (expr instanceof BinaryExpression) {
            computeConditionVariables(context, (BinaryExpression) expr, pLevel);
        }

    }

    /**
     * Analyze and compute variables usage for ELSE AST structure
     * @param pElseTree ELSE node
     * @param pLevel the level of ELSE node
     */
    private void visitElseNode(SubscriptionContext context, ElseClause pElseTree, int pLevel) {

        if (pElseTree == null) { return; }

        // analyze variables and raise error if needed
        computeElseVariables(context, pElseTree, pLevel);

        // go to next child level
        visitNodeContent(context, pElseTree.body().statements(), pLevel + 1);
    }

    /**
     * Analyze and compute variables usage for ELSE AST structure
     * @param pElseTree ELSE node
     * @param pLevel the level of ELSE node
     */
    private void computeElseVariables(SubscriptionContext context, ElseClause pElseTree, int pLevel) {

        Map<String, Integer> mapVariables = variablesStruct.getVariablesForCurrentIfStruct(pLevel);

        // specific use case : if there is no variables used in any conditions of IF / ELSEIF structures,
        // we could have a NullPointerException if we don't check this case
        if (mapVariables == null || mapVariables.isEmpty()) { return; }

        for (Map.Entry<String, Integer> entry : mapVariables.entrySet()) {
            String variableName = entry.getKey();

            // increment usage of all variables in the same level of ELSE staetement
            int nbUsed = variablesStruct.incrementVariableUsageForLevel(variableName, pLevel);

            // increment variable counter to list of variables already declared for current if or elseif struture
            variablesStruct.incrementVariableUsageForLevelForCurrentIfStruct(variableName, pLevel);

            // raise an error if maximum
            if (nbUsed > 2) {
                context.addIssue(pElseTree.firstToken(), ERROR_MESSAGE);
            }
        }
    }

    /**
     * Complex data structure representing variables counters per AST level (cumulative counts with parent levels)
     *  Map<Integer, Map<String, Integer>> ==>
     *  - Key : index of Level (0 = first level)
     *  - Value : Map<String, Integer>
     *      - Key : name of variable in the current or parent level
     *      - Value : number of usage of this variable in an IF statement in current level or one of parent levels
     *
     */
    private static class VariablesPerLevelDataStructure {

        // global map variable counters per level
        private final Map<Integer, Map<String, Integer>> mapVariablesPerLevel;

        // map variable counters per level for current If / ElseIf structure
        // purpose : used by compute variables Else process (because Else structure is particular : 
        // we don't know previous variables, and we need previous If / ElseIf structure to know variables)
        private final Map<Integer, Map<String, Integer>> mapVariablesPerLevelForCurrentIfStruct;

        public VariablesPerLevelDataStructure() {
            mapVariablesPerLevel = new HashMap<>(10);
            mapVariablesPerLevelForCurrentIfStruct = new HashMap<>(10);
        }

        /**
         * increment variable counters on global map
         */
        public int incrementVariableUsageForLevel(String variableName, int pLevel) {
            return internalIncrementVariableUsage(mapVariablesPerLevel, variableName, pLevel);
        }

        /**
         * increment variable counters on input map
         */
        private int internalIncrementVariableUsage(Map<Integer, Map<String, Integer>> pDataMap, String variableName, int pLevel) {

            // get variable usage map for current level and init if null
            Map<String, Integer> variablesMap = pDataMap.computeIfAbsent(pLevel, k -> new HashMap<>(5));

            // get usage from parent if needed
            Integer nbUsed = variablesMap.get(variableName);
            if (nbUsed == null) {
                Integer nbParentUsed = internalGetVariableUsageOfNearestParent(pDataMap, variableName, pLevel - 1);
                nbUsed = nbParentUsed == null ? 0 : nbParentUsed;
            }

            // increment usage for current level
            nbUsed++;
            variablesMap.put(variableName, nbUsed);

            return nbUsed;
        }

        /**
         * get usage of a variable in top tree (nearest top parent)
         */
        private Integer internalGetVariableUsageOfNearestParent(Map<Integer, Map<String, Integer>> pDataMap, String variableName, int pLevel) {

            Integer nbParentUsed = null;
            for (int i = pLevel; i >= 0 && nbParentUsed == null; i--) {
                Map<String, Integer> variablesParentLevelMap = pDataMap.get(i);
                nbParentUsed = variablesParentLevelMap.get(variableName);
            }

            return nbParentUsed;
        }

        /**
         * reinitialization of variable usages for input level and global map
         */
        public void reinitVariableUsageForLevel(int pLevel) {
            internalReinitVariableUsageForLevelForCurrentIfStruct(mapVariablesPerLevel, pLevel);
        }

        /**
         * reinitialization of variable usages in input level in input map
         */
        private void internalReinitVariableUsageForLevelForCurrentIfStruct(Map<Integer, Map<String, Integer>> pDataMap, int pLevel) {
            if (pDataMap.get(pLevel) == null) { return; }

            // cleaning of current If Structure beginning at level specified
            for (int i = pLevel; i < pDataMap.size(); i++) {
                pDataMap.remove(i);
            }

        }

        /**
         * reinitialization of variable usages for input level on if/elseif map
         */
        public void reinitVariableUsageForLevelForCurrentIfStruct(int pLevel) {
            internalReinitVariableUsageForLevelForCurrentIfStruct(mapVariablesPerLevelForCurrentIfStruct, pLevel);
        }

        /**
         * increment variable counters on if/elseif map
         */
        public void incrementVariableUsageForLevelForCurrentIfStruct(String variableName, int pLevel) {
            internalIncrementVariableUsage(mapVariablesPerLevelForCurrentIfStruct, variableName, pLevel);
        }

        /**
         * get usage of a variable in a level on if/elseif map
         */
        public Map<String, Integer> getVariablesForCurrentIfStruct(int pLevel) {
            return mapVariablesPerLevelForCurrentIfStruct.get(pLevel);
        }

    }

}
