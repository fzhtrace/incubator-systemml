/**
 * IBM Confidential
 * OCO Source Materials
 * (C) Copyright IBM Corp. 2010, 2015
 * The source code for this program is not published or otherwise divested of its trade secrets, irrespective of what has been deposited with the U.S. Copyright Office.
 */

package com.ibm.bi.dml.parser;

import java.util.HashMap;

import com.ibm.bi.dml.parser.LanguageException.LanguageErrorCodes;

public class RelationalExpression extends Expression
{
	@SuppressWarnings("unused")
	private static final String _COPYRIGHT = "Licensed Materials - Property of IBM\n(C) Copyright IBM Corp. 2010, 2015\n" +
                                             "US Government Users Restricted Rights - Use, duplication  disclosure restricted by GSA ADP Schedule Contract with IBM Corp.";
		
	private Expression _left;
	private Expression _right;
	private RelationalOp _opcode;
	
	public RelationalExpression(RelationalOp bop) {
		_kind = Kind.RelationalOp;
		_opcode = bop;
		
		setFilename("MAIN SCRIPT");
		setBeginLine(0);
		setBeginColumn(0);
		setEndLine(0);
		setEndColumn(0);
	}
	
	public RelationalExpression(RelationalOp bop, String filename, int beginLine, int beginColumn, int endLine, int endColumn) {
		_kind = Kind.RelationalOp;
		_opcode = bop;
		
		setFilename(filename);
		setBeginLine(beginLine);
		setBeginColumn(beginColumn);
		setEndLine(endLine);
		setEndColumn(endColumn);
	}
	
	public Expression rewriteExpression(String prefix) throws LanguageException{
		
		RelationalExpression newExpr = new RelationalExpression(this._opcode, getFilename(), getBeginLine(), getBeginColumn(), getEndLine(), getEndColumn());
		newExpr.setLeft(_left.rewriteExpression(prefix));
		newExpr.setRight(_right.rewriteExpression(prefix));
		return newExpr;
	}
	
	public RelationalOp getOpCode(){
		return _opcode;
	}
	
	public void setLeft(Expression l){
		_left = l;
		
		// update script location information --> left expression is BEFORE in script
		if (_left != null){
			setFilename(_left.getFilename());
			setBeginLine(_left.getBeginLine());
			setBeginColumn(_left.getBeginColumn());
		}
		
	}
	
	public void setRight(Expression r){
		_right = r;
		
		// update script location information --> right expression is AFTER in script
		if (_right != null){
			setFilename(_right.getFilename());
			setBeginLine(_right.getEndLine());
			setBeginColumn(_right.getEndColumn());
		}
	}
	
	public Expression getLeft(){
		return _left;
	}
	
	public Expression getRight(){
		return _right;
	}

	/**
	 * Validate parse tree : Process Relational Expression  
	 * @throws LanguageException 
	 */
	@Override
	public void validateExpression(HashMap<String,DataIdentifier> ids, HashMap<String, ConstIdentifier> constVars, boolean conditional) 
		throws LanguageException
	{	
		//check for functions calls in expression
		if (_left instanceof FunctionCallIdentifier){
			raiseValidateError("user-defined function calls not supported in relational expressions", 
		            false, LanguageException.LanguageErrorCodes.UNSUPPORTED_EXPRESSION);
		}		
		if (_right instanceof FunctionCallIdentifier){
			raiseValidateError("user-defined function calls not supported in relational expressions", 
		            false, LanguageException.LanguageErrorCodes.UNSUPPORTED_EXPRESSION);
		}
		
		// handle <NUMERIC> == <BOOLEAN> --> convert <BOOLEAN> to numeric value
		if ((_left != null && _left instanceof BooleanIdentifier) || (_right != null && _right instanceof BooleanIdentifier)){
			if ((_left instanceof IntIdentifier || _left instanceof DoubleIdentifier) || _right instanceof IntIdentifier || _right instanceof DoubleIdentifier){
				if (_left instanceof BooleanIdentifier){
					if (((BooleanIdentifier) _left).getValue())
						this.setLeft(new IntIdentifier(1, _left.getFilename(), _left.getBeginLine(), _left.getBeginColumn(), _left.getEndLine(), _left.getEndColumn()));
					else
						this.setLeft(new IntIdentifier(0, _left.getFilename(), _left.getBeginLine(), _left.getBeginColumn(), _left.getEndLine(), _left.getEndColumn()));
				}
				else if (_right instanceof BooleanIdentifier){
					if (((BooleanIdentifier) _right).getValue())
						this.setRight(new IntIdentifier(1, _right.getFilename(), _right.getBeginLine(), _right.getBeginColumn(), _right.getEndLine(),_right.getEndColumn()));
					else
						this.setRight(new IntIdentifier(0,  _right.getFilename(), _right.getBeginLine(), _right.getBeginColumn(), _right.getEndLine(),_right.getEndColumn()));
				}
			}
		}
		
		//recursive validate
		_left.validateExpression(ids, constVars, conditional);
		if( _right !=null )
			_right.validateExpression(ids, constVars, conditional);
		
		//constant propagation (precondition for more complex constant folding rewrite)
		if( _left instanceof DataIdentifier && constVars.containsKey(((DataIdentifier) _left).getName()) )
			_left = constVars.get(((DataIdentifier) _left).getName());
		if( _right instanceof DataIdentifier && constVars.containsKey(((DataIdentifier) _right).getName()) )
			_right = constVars.get(((DataIdentifier) _right).getName());
		
		String outputName = getTempName();
		DataIdentifier output = new DataIdentifier(outputName);
		output.setAllPositions(this.getFilename(), this.getBeginLine(), this.getBeginColumn(), this.getEndLine(), this.getEndColumn());
		
		boolean isLeftMatrix = (_left.getOutput() != null && _left.getOutput().getDataType() == DataType.MATRIX);
		boolean isRightMatrix = (_right.getOutput() != null && _right.getOutput().getDataType() == DataType.MATRIX); 
		if(isLeftMatrix || isRightMatrix) {
			// Added to support matrix relational comparison
			if(isLeftMatrix && isRightMatrix) {
				checkMatchingDimensions(_left, _right, true);
			}
			
			long[] dims = getBinaryMatrixCharacteristics(_left, _right);
			output.setDataType(DataType.MATRIX);
			output.setDimensions(dims[0], dims[1]);
			output.setBlockDimensions(dims[2], dims[3]);
			output.setValueType(ValueType.BOOLEAN);
		}
		else {
			output.setBooleanProperties();
		}
		
		this.setOutput(output);
	}		
	
	/**
	 * This is same as the function from BuiltinFunctionExpression which is called by ppred
	 * @param expr1
	 * @param expr2
	 * @throws LanguageException
	 */
	private void checkMatchingDimensions(Expression expr1, Expression expr2, boolean allowsMV) 
		throws LanguageException 
	{
		if (expr1 != null && expr2 != null) {
			
			// if any matrix has unknown dimensions, simply return
			if(  expr1.getOutput().getDim1() == -1 || expr2.getOutput().getDim1() == -1 
			   ||expr1.getOutput().getDim2() == -1 || expr2.getOutput().getDim2() == -1 ) 
			{
				return;
			}
			else if( (!allowsMV && expr1.getOutput().getDim1() != expr2.getOutput().getDim1())
				  || (allowsMV && expr1.getOutput().getDim1() != expr2.getOutput().getDim1() && expr2.getOutput().getDim1() != 1)
				  || (!allowsMV && expr1.getOutput().getDim2() != expr2.getOutput().getDim2()) 
				  || (allowsMV && expr1.getOutput().getDim2() != expr2.getOutput().getDim2() && expr2.getOutput().getDim2() != 1) ) 
			{
				raiseValidateError("Mismatch in matrix dimensions of parameters for function "
						+ this.getOpCode(), false, LanguageErrorCodes.INVALID_PARAMETERS);
			}
		}
	}
	
	/**
	 * This is same as the function from BuiltinFunctionExpression which is called by ppred
	 * 
	 * Returns the matrix characteristics for scalar-matrix, matrix-scalar, matrix-matrix
	 * operations. Format: rlen, clen, brlen, bclen.
	 * 
	 * @param left
	 * @param right
	 * @return
	 */
	private static long[] getBinaryMatrixCharacteristics( Expression left, Expression right )
	{
		long[] ret = new long[]{ -1, -1, -1, -1 };
		Identifier idleft = left.getOutput();
		Identifier idright = right.getOutput();
		
		//rlen known
		if( idleft.getDim1()>0 || idright.getDim1()>0 )
			ret[ 0 ] = Math.max(idleft.getDim1(), idright.getDim1());
		
		//rlen known
		if( idleft.getDim2()>0 || idright.getDim2()>0 )
			ret[ 1 ] = Math.max(idleft.getDim2(), idright.getDim2());
		
		//brlen known
		if( idleft.getRowsInBlock()>0 || idright.getRowsInBlock()>0 )
			ret[ 2 ] = Math.max(idleft.getRowsInBlock(), idright.getRowsInBlock());
		
		//bclen known
		if( idleft.getColumnsInBlock()>0 || idright.getColumnsInBlock()>0 )
			ret[ 3 ] = Math.max(idleft.getColumnsInBlock(), idright.getColumnsInBlock());
		
		return ret;
	}
	
	public String toString(){
		return "(" + _left.toString() + " " + _opcode.toString() + " " + _right.toString() + ")";
	}
	@Override
	public VariableSet variablesRead() {
		VariableSet result = new VariableSet();
		result.addVariables(_left.variablesRead());
		result.addVariables(_right.variablesRead());
		return result;
	}

	@Override
	public VariableSet variablesUpdated() {
		VariableSet result = new VariableSet();
		result.addVariables(_left.variablesUpdated());
		result.addVariables(_right.variablesUpdated());
		return result;
	}
	
}