/*
 * Copyright (C) 2010-2011 The Project Lombok Authors.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package lombok.eclipse.agent;

import static lombok.eclipse.handlers.EclipseHandlerUtil.*;
import static lombok.eclipse.Eclipse.*;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import lombok.core.AST.Kind;
import lombok.eclipse.EclipseAST;
import lombok.eclipse.EclipseNode;
import lombok.eclipse.TransformEclipseAST;

import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AbstractMethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Annotation;
import org.eclipse.jdt.internal.compiler.ast.Argument;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.ClassLiteralAccess;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.FieldDeclaration;
import org.eclipse.jdt.internal.compiler.ast.FieldReference;
import org.eclipse.jdt.internal.compiler.ast.MarkerAnnotation;
import org.eclipse.jdt.internal.compiler.ast.MemberValuePair;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.QualifiedTypeReference;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.SingleNameReference;
import org.eclipse.jdt.internal.compiler.ast.SingleTypeReference;
import org.eclipse.jdt.internal.compiler.ast.Statement;
import org.eclipse.jdt.internal.compiler.ast.ThisReference;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeParameter;
import org.eclipse.jdt.internal.compiler.ast.TypeReference;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.lookup.AnnotationBinding;
import org.eclipse.jdt.internal.compiler.lookup.ArrayBinding;
import org.eclipse.jdt.internal.compiler.lookup.BaseTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.ClassScope;
import org.eclipse.jdt.internal.compiler.lookup.MethodBinding;
import org.eclipse.jdt.internal.compiler.lookup.ParameterizedTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.SourceTypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeBinding;
import org.eclipse.jdt.internal.compiler.lookup.TypeConstants;
import org.eclipse.jdt.internal.compiler.lookup.TypeVariableBinding;
import org.eclipse.jdt.internal.compiler.lookup.UnresolvedReferenceBinding;
import org.eclipse.jdt.internal.compiler.lookup.WildcardBinding;

public class PatchDelegate {
	private static class ClassScopeEntry {
		ClassScopeEntry(ClassScope scope) {
			this.scope = scope;
		}
		
		final ClassScope scope;
		String corruptedPath;
	}
	
	private static ThreadLocal<List<ClassScopeEntry>> visited = new ThreadLocal<List<ClassScopeEntry>>() {
		protected List<ClassScopeEntry> initialValue() {
			return new ArrayList<ClassScopeEntry>();
		}
	};
	
	private static String nameOfScope(ClassScope scope) {
		TypeDeclaration decl = scope.referenceContext;
		if (decl == null) return "(unknown)";
		if (decl.name == null || decl.name.length == 0) return "(unknown)";
		return new String(decl.name);
	}
	
	private static boolean hasDelegateMarkedFields(TypeDeclaration decl) {
		if (decl.fields != null) for (FieldDeclaration field : decl.fields) {
			if (field.annotations == null) continue;
			for (Annotation ann : field.annotations) {
				if (ann.type == null) continue;
				TypeBinding tb = ann.type.resolveType(decl.initializerScope);
				if (!charArrayEquals("lombok", tb.qualifiedPackageName())) continue;
				if (!charArrayEquals("Delegate", tb.qualifiedSourceName())) continue;
				return true;
			}
		}
		
		return false;
	}
	
	public static boolean handleDelegateForType(ClassScope scope) {
		if (TransformEclipseAST.disableLombok) return false;
		if (!hasDelegateMarkedFields(scope.referenceContext)) return false;
		
		List<ClassScopeEntry> stack = visited.get();
		StringBuilder corrupted = null;
		for (ClassScopeEntry entry : stack) {
			if (corrupted != null) {
				corrupted.append(" -> ").append(nameOfScope(entry.scope));
			} else if (entry.scope == scope) {
				corrupted = new StringBuilder().append(nameOfScope(scope));
			}
		}
		
		if (corrupted != null) {
			boolean found = false;
			String path = corrupted.toString();
			for (ClassScopeEntry entry : stack) {
				if (!found && entry.scope == scope) found = true;
				if (found) entry.corruptedPath = path;
			}
		} else {
			ClassScopeEntry entry = new ClassScopeEntry(scope);
			stack.add(entry);
			
			try {
				List<BindingTuple> methodsToDelegate = new ArrayList<BindingTuple>();
				TypeDeclaration decl = scope.referenceContext;
				if (decl != null) {
					CompilationUnitDeclaration cud = scope.compilationUnitScope().referenceContext;
					EclipseAST eclipseAst = TransformEclipseAST.getAST(cud, true);
					fillMethodBindings(cud, scope, methodsToDelegate);
					if (entry.corruptedPath != null) {
						eclipseAst.get(scope.referenceContext).addError("No @Delegate methods created because there's a loop: " + entry.corruptedPath);
					} else {
						generateDelegateMethods(eclipseAst.get(decl), methodsToDelegate);
					}
				}
			} finally {
				stack.remove(stack.size() - 1);
			}
		}
		
		return false;
	}
	
	/**
	 * Returns a string containing the signature of a method that appears (erased) at least twice in the list.
	 * If no duplicates are present, {@code null} is returned.
	 */
	private static String containsDuplicates(List<BindingTuple> tuples) {
		Set<String> sigs = new HashSet<String>();
		for (BindingTuple tuple : tuples) {
			if (!sigs.add(printSig(tuple.parameterized))) return printSig(tuple.parameterized);
		}
		
		return null;
	}
	
	private static Map<ASTNode, Object> alreadyApplied = new WeakHashMap<ASTNode, Object>();
	private static final Object MARKER = new Object();
	
	private static void fillMethodBindings(CompilationUnitDeclaration cud, ClassScope scope, List<BindingTuple> methodsToDelegate) {
		TypeDeclaration decl = scope.referenceContext;
		if (decl == null) return;
		
		if (decl.fields != null) for (FieldDeclaration field : decl.fields) {
			if (field.annotations == null) continue;
			for (Annotation ann : field.annotations) {
				if (ann.type == null) continue;
				TypeBinding tb = ann.type.resolveType(decl.initializerScope);
				if (!charArrayEquals("lombok", tb.qualifiedPackageName())) continue;
				if (!charArrayEquals("Delegate", tb.qualifiedSourceName())) continue;
				if (alreadyApplied.put(ann, MARKER) == MARKER) continue;
				
				List<ClassLiteralAccess> rawTypes = new ArrayList<ClassLiteralAccess>();
				List<ClassLiteralAccess> excludedRawTypes = new ArrayList<ClassLiteralAccess>();
				for (MemberValuePair pair : ann.memberValuePairs()) {
					if (charArrayEquals("types", pair.name)) {
						if (pair.value instanceof ArrayInitializer) {
							for (Expression expr : ((ArrayInitializer)pair.value).expressions) {
								if (expr instanceof ClassLiteralAccess) rawTypes.add((ClassLiteralAccess) expr);
							}
						}
						if (pair.value instanceof ClassLiteralAccess) {
							rawTypes.add((ClassLiteralAccess) pair.value);
						}
					}
					if (charArrayEquals("excludes", pair.name)) {
						if (pair.value instanceof ArrayInitializer) {
							for (Expression expr : ((ArrayInitializer)pair.value).expressions) {
								if (expr instanceof ClassLiteralAccess) excludedRawTypes.add((ClassLiteralAccess) expr);
							}
						}
						if (pair.value instanceof ClassLiteralAccess) {
							excludedRawTypes.add((ClassLiteralAccess) pair.value);
						}
					}
				}
				
				List<BindingTuple> methodsToExclude = new ArrayList<BindingTuple>();
				for (ClassLiteralAccess cla : excludedRawTypes) {
					addAllMethodBindings(methodsToExclude, cla.type.resolveType(decl.initializerScope), new HashSet<String>(), field.name, ann);
				}
				
				Set<String> banList = new HashSet<String>();
				for (BindingTuple excluded : methodsToExclude) banList.add(printSig(excluded.parameterized));
				
				List<BindingTuple> methodsToDelegateForThisAnn = new ArrayList<BindingTuple>();
				
				if (rawTypes.isEmpty()) {
					addAllMethodBindings(methodsToDelegateForThisAnn, field.type.resolveType(decl.initializerScope), banList, field.name, ann);
				} else {
					for (ClassLiteralAccess cla : rawTypes) {
						addAllMethodBindings(methodsToDelegateForThisAnn, cla.type.resolveType(decl.initializerScope), banList, field.name, ann);
					}
				}
				
				// Not doing this right now because of problems - see commented-out-method for info.
				// removeExistingMethods(methodsToDelegate, decl, scope);
				
				String dupe = containsDuplicates(methodsToDelegateForThisAnn);
				if (dupe != null) {
					EclipseAST eclipseAst = TransformEclipseAST.getAST(cud, true);
					eclipseAst.get(ann).addError("The method '" + dupe + "' is being delegated by more than one specified type.");
				} else {
					methodsToDelegate.addAll(methodsToDelegateForThisAnn);
				}
			}
		}
	}
	
	/*
	 * We may someday finish this method. Steps to be completed:
	 * 
	 * (A) Turn any Parameterized anythings into non-parameterized versions. Resolving parameterized stuff will definitely not work safely.
	 * (B) scope.problemReporter() will need to return a noop reporter as various errors are marked off.
	 * (C) Find a way to do _something_ for references to typevars (i.e. 'T') which are declared on the method itself.
	 * (D) getTypeBinding isn't public, so call it via reflection.
	 */
//	private static TypeBinding safeResolveAndErase(TypeReference ref, Scope scope) {
//		if (ref.resolvedType != null) {
//			return ref.resolvedType.isValidBinding() ? ref.resolvedType : null;
//		}
//		
//		try {
//			TypeBinding bind = ref.getTypeBinding(scope);
//			if (!bind.isValidBinding()) return null;
//		} catch (AbortCompilation e) {
//			return null;
//		}
//		return bind.erasure();
//	}
	
	/*
	 * Not using this because calling clone.resolveType() taints a bunch of caches and reports erroneous errors.
	 */
//	private static void removeExistingMethods(List<BindingTuple> list, TypeDeclaration decl, ClassScope scope) {
//		for (AbstractMethodDeclaration methodDecl : decl.methods) {
//			if (!(methodDecl instanceof MethodDeclaration)) continue;
//			MethodDeclaration md = (MethodDeclaration) methodDecl;
//			char[] name = md.selector;
//			TypeBinding[] args = md.arguments == null ? new TypeBinding[0] : new TypeBinding[md.arguments.length];
//			for (int i = 0; i < args.length; i++) {
//				TypeReference clone = Eclipse.copyType(md.arguments[i].type, md.arguments[i]);
//				args[i] = clone.resolveType(scope).erasure(); // This is the problematic line
//			}
//			Iterator<BindingTuple> it = list.iterator();
//			methods:
//			while (it.hasNext()) {
//				MethodBinding mb = it.next().parameterized;
//				if (!Arrays.equals(mb.selector, name)) continue;
//				int paramLen = mb.parameters == null ? 0 : mb.parameters.length;
//				if (paramLen != args.length) continue;
//				if (md.typeParameters == null || md.typeParameters.length == 0) {
//					for (int i = 0; i < paramLen; i++) {
//						if (!mb.parameters[i].erasure().isEquivalentTo(args[i])) continue methods;
//					}
//				} else {
//					for (int i = 0; i < paramLen; i++) {
//						if (!mb.parameters[i].erasure().isEquivalentTo(args[i])) ;
//					}
//					//BUG #???: We erase the method's parameter types using  the class scope, but we should be using the method scope.
//					// In practice this is no problem UNLESS the method has type parameters, such as <T> T[] toArray(T[] in).
//					// In this case the class scope cannot resolve the T[] parameter and erase it to Object[], which is a big problem because
//					// it would mean manually writing <X> X[] toArray(X[] in) does NOT stop lombok from ALSO trying to make the delegated toArray method,
//					// thus causing an error (2 methods with post-erasure duplicate signatures). Our 'fix' for this is to treat any method with type parameters
//					// as if each parameter's type matches anything else; so, if the name matches and the parameter count, we DONT generate it, even if its just
//					// an overloaded method.
//					//
//					// The reason we do this now is because making that MethodScope properly is effectively impossible at this step, so we need to do the resolving
//					// ourselves, which involves chasing down array bindings (T[]), following the path down type variables, i.e. <X extends Y, Y extends T>, and then
//					// resolving the final result of this exercise against the class scope.
//					
//					// When this crappy incomplete workaround of ours occurs, we end up in this else block, which does nothing and thus we fall through and remove
//					// the method.
//				}
//				it.remove(); // Method already exists in this class - don't create a delegating implementation.
//			}
//		}
//	}
	
	private static void generateDelegateMethods(EclipseNode typeNode, List<BindingTuple> methods) {
		CompilationUnitDeclaration top = (CompilationUnitDeclaration) typeNode.top().get();
		for (BindingTuple pair : methods) {
			EclipseNode annNode = typeNode.getAst().get(pair.responsible);
			MethodDeclaration method = createDelegateMethod(pair.fieldName, typeNode, pair, top.compilationResult, annNode);
			if (method != null) injectMethod(typeNode, method);
		}
	}
	
	private static boolean hasDeprecatedAnnotation(MethodBinding binding) {
		AnnotationBinding[] annotations = binding.getAnnotations();
		if (annotations != null) for (AnnotationBinding ann : annotations) {
			ReferenceBinding annType = ann.getAnnotationType();
			char[] pkg = annType.qualifiedPackageName();
			char[] src = annType.qualifiedSourceName();
			
			if (charArrayEquals("java.lang", pkg) && charArrayEquals("Deprecated", src)) return true;
		}
		
		return false;
	}
	
	public static void checkConflictOfTypeVarNames(BindingTuple binding, EclipseNode typeNode) throws CantMakeDelegates {
		TypeVariableBinding[] typeVars = binding.parameterized.typeVariables();
		if (typeVars == null || typeVars.length == 0) return;
		
		Set<String> usedInOurType = new HashSet<String>();
		EclipseNode enclosingType = typeNode;
		while (enclosingType != null) {
			if (enclosingType.getKind() == Kind.TYPE) {
				TypeParameter[] typeParameters = ((TypeDeclaration)enclosingType.get()).typeParameters;
				if (typeParameters != null) {
					for (TypeParameter param : typeParameters) {
						if (param.name != null) usedInOurType.add(new String(param.name));
					}
				}
			}
			enclosingType = enclosingType.up();
		}
		
		Set<String> usedInMethodSig = new HashSet<String>();
		for (TypeVariableBinding var : typeVars) {
			char[] sourceName = var.sourceName();
			if (sourceName != null) usedInMethodSig.add(new String(sourceName));
		}
		
		usedInMethodSig.retainAll(usedInOurType);
		if (usedInMethodSig.isEmpty()) return;
		
		// We might be delegating a List<T>, and we are making method <T> toArray(). A conflict is possible.
		// But only if the toArray method also uses type vars from its class, otherwise we're only shadowing,
		// which is okay as we'll add a @SuppressWarnings.
		
		TypeVarFinder finder = new TypeVarFinder();
		finder.visitRaw(binding.base);
		
		Set<String> names = new HashSet<String>(finder.getTypeVariables());
		names.removeAll(usedInMethodSig);
		if (!names.isEmpty()) {
			// We have a confirmed conflict. We could dig deeper as this may still be a false alarm, but its already an exceedingly rare case.
			CantMakeDelegates cmd = new CantMakeDelegates();
			cmd.conflicted = usedInMethodSig;
			throw cmd;
		}
	}
	
	public static class CantMakeDelegates extends Exception {
		public Set<String> conflicted;
	}
	
	public static class TypeVarFinder extends EclipseTypeBindingScanner {
		private Set<String> typeVars = new HashSet<String>();
		
		public Set<String> getTypeVariables() {
			return typeVars;
		}
		
		@Override public void visitTypeVariable(TypeVariableBinding binding) {
			if (binding.sourceName != null) typeVars.add(new String(binding.sourceName));
			super.visitTypeVariable(binding);
		}
	}
	
	public abstract static class EclipseTypeBindingScanner {
		public void visitRaw(Binding binding) {
			if (binding == null) return;
			if (binding instanceof MethodBinding) visitMethod((MethodBinding) binding);
			if (binding instanceof BaseTypeBinding) visitBase((BaseTypeBinding) binding);
			if (binding instanceof ArrayBinding) visitArray((ArrayBinding) binding);
			if (binding instanceof UnresolvedReferenceBinding) visitUnresolved((UnresolvedReferenceBinding) binding);
			if (binding instanceof WildcardBinding) visitWildcard((WildcardBinding) binding);
			if (binding instanceof TypeVariableBinding) visitTypeVariable((TypeVariableBinding) binding);
			if (binding instanceof ParameterizedTypeBinding) visitParameterized((ParameterizedTypeBinding) binding);
			if (binding instanceof ReferenceBinding) visitReference((ReferenceBinding) binding);
		}
		
		public void visitReference(ReferenceBinding binding) {
		}
		
		public void visitParameterized(ParameterizedTypeBinding binding) {
			visitRaw(binding.genericType());
			TypeVariableBinding[] typeVars = binding.typeVariables();
			if (typeVars != null) for (TypeVariableBinding child : typeVars) {
				visitRaw(child);
			}
		}
		
		public void visitTypeVariable(TypeVariableBinding binding) {
			visitRaw(binding.superclass);
			ReferenceBinding[] supers = binding.superInterfaces();
			if (supers != null) for (ReferenceBinding child : supers) {
				visitRaw(child);
			}
		}
		
		public void visitWildcard(WildcardBinding binding) {
			visitRaw(binding.bound);
		}
		
		public void visitUnresolved(UnresolvedReferenceBinding binding) {
		}
		
		public void visitArray(ArrayBinding binding) {
			visitRaw(binding.leafComponentType());
		}
		
		public void visitBase(BaseTypeBinding binding) {
		}
		
		public void visitMethod(MethodBinding binding) {
			if (binding.parameters != null) for (TypeBinding child : binding.parameters) {
				visitRaw(child);
			}
			visitRaw(binding.returnType);
			if (binding.thrownExceptions != null) for (TypeBinding child : binding.thrownExceptions) {
				visitRaw(child);
			}
			TypeVariableBinding[] typeVars = binding.typeVariables();
			if (typeVars != null) for (TypeVariableBinding child : typeVars) {
				visitRaw(child.superclass);
				ReferenceBinding[] supers = child.superInterfaces();
				if (supers != null) for (ReferenceBinding child2 : supers) {
					visitRaw(child2);
				}
			}
		}
	}
	
	private static MethodDeclaration createDelegateMethod(char[] name, EclipseNode typeNode, BindingTuple pair, CompilationResult compilationResult, EclipseNode annNode) {
		/* public <T, U, ...> ReturnType methodName(ParamType1 name1, ParamType2 name2, ...) throws T1, T2, ... {
		 *      (return) delegate.<T, U>methodName(name1, name2);
		 *  }
		 */
		
		boolean isVarargs = (pair.base.modifiers & ClassFileConstants.AccVarargs) != 0;
		
		try {
			checkConflictOfTypeVarNames(pair, typeNode);
		} catch (CantMakeDelegates e) {
			annNode.addError("There's a conflict in the names of type parameters. Fix it by renaming the following type parameters of your class: " + e.conflicted);
			return null;
		}
		
		ASTNode source = annNode.get();
		
		int pS = source.sourceStart, pE = source.sourceEnd;
		
		MethodBinding binding = pair.parameterized;
		MethodDeclaration method = new MethodDeclaration(compilationResult);
		setGeneratedBy(method, source);
		method.sourceStart = pS; method.sourceEnd = pE;
		method.modifiers = ClassFileConstants.AccPublic;
		
		method.returnType = makeType(binding.returnType, source, false);
		boolean isDeprecated = hasDeprecatedAnnotation(binding);
		
		method.selector = binding.selector;
		
		if (binding.thrownExceptions != null && binding.thrownExceptions.length > 0) {
			method.thrownExceptions = new TypeReference[binding.thrownExceptions.length];
			for (int i = 0; i < method.thrownExceptions.length; i++) {
				method.thrownExceptions[i] = makeType(binding.thrownExceptions[i], source, false);
			}
		}
		
		MessageSend call = new MessageSend();
		call.sourceStart = pS; call.sourceEnd = pE;
		call.nameSourcePosition = pos(source);
		setGeneratedBy(call, source);
		FieldReference fieldRef = new FieldReference(name, pos(source));
		fieldRef.receiver = new ThisReference(pS, pE);
		setGeneratedBy(fieldRef, source);
		setGeneratedBy(fieldRef.receiver, source);
		call.receiver = fieldRef;
		call.selector = binding.selector;
		
		if (binding.typeVariables != null && binding.typeVariables.length > 0) {
			method.typeParameters = new TypeParameter[binding.typeVariables.length];
			call.typeArguments = new TypeReference[binding.typeVariables.length];
			for (int i = 0; i < method.typeParameters.length; i++) {
				method.typeParameters[i] = new TypeParameter();
				method.typeParameters[i].sourceStart = pS; method.typeParameters[i].sourceEnd = pE;
				setGeneratedBy(method.typeParameters[i], source);
				method.typeParameters[i].name = binding.typeVariables[i].sourceName;
				call.typeArguments[i] = new SingleTypeReference(binding.typeVariables[i].sourceName, pos(source));
				setGeneratedBy(call.typeArguments[i], source);
				ReferenceBinding super1 = binding.typeVariables[i].superclass;
				ReferenceBinding[] super2 = binding.typeVariables[i].superInterfaces;
				if (super2 == null) super2 = new ReferenceBinding[0];
				if (super1 != null || super2.length > 0) {
					int offset = super1 == null ? 0 : 1;
					method.typeParameters[i].bounds = new TypeReference[super2.length + offset - 1];
					if (super1 != null) method.typeParameters[i].type = makeType(super1, source, false);
					else method.typeParameters[i].type = makeType(super2[0], source, false);
					int ctr = 0;
					for (int j = (super1 == null) ? 1 : 0; j < super2.length; j++) {
						method.typeParameters[i].bounds[ctr] = makeType(super2[j], source, false);
						method.typeParameters[i].bounds[ctr++].bits |= ASTNode.IsSuperType;
					}
				}
			}
		}
		
		if (isDeprecated) {
			QualifiedTypeReference qtr = new QualifiedTypeReference(new char[][] {
					{'j', 'a', 'v', 'a'}, {'l', 'a', 'n', 'g'}, {'D', 'e', 'p', 'r', 'e', 'c', 'a', 't', 'e', 'd'}}, poss(source, 3));
			setGeneratedBy(qtr, source);
			MarkerAnnotation ann = new MarkerAnnotation(qtr, pS);
			method.annotations = new Annotation[] {ann};
		}
		
		method.bits |= ECLIPSE_DO_NOT_TOUCH_FLAG;
		
		if (binding.parameters != null && binding.parameters.length > 0) {
			method.arguments = new Argument[binding.parameters.length];
			call.arguments = new Expression[method.arguments.length];
			for (int i = 0; i < method.arguments.length; i++) {
				AbstractMethodDeclaration sourceElem = pair.base.sourceMethod();
				char[] argName;
				if (sourceElem == null) argName = ("arg" + i).toCharArray();
				else {
					argName = sourceElem.arguments[i].name;
				}
				method.arguments[i] = new Argument(
						argName, pos(source),
						makeType(binding.parameters[i], source, false),
						ClassFileConstants.AccFinal);
				setGeneratedBy(method.arguments[i], source);
				call.arguments[i] = new SingleNameReference(argName, pos(source));
				setGeneratedBy(call.arguments[i], source);
			}
			if (isVarargs) {
				method.arguments[method.arguments.length - 1].type.bits |= ASTNode.IsVarArgs;
			}
		}
		
		Statement body;
		if (method.returnType instanceof SingleTypeReference && ((SingleTypeReference)method.returnType).token == TypeConstants.VOID) {
			body = call;
		} else {
			body = new ReturnStatement(call, source.sourceStart, source.sourceEnd);
			setGeneratedBy(body, source);
		}
		
		method.statements = new Statement[] {body};
		return method;
	}
	
	private static final class Reflection {
		public static final Method classScopeBuildFieldsAndMethodsMethod;
		
		static {
			Method m = null;
			try {
				m = ClassScope.class.getDeclaredMethod("buildFieldsAndMethods");
				m.setAccessible(true);
			} catch (Exception e) {
				// That's problematic, but as long as no local classes are used we don't actually need it.
				// Better fail on local classes than crash altogether.
			}
			
			classScopeBuildFieldsAndMethodsMethod = m;
		}
	}
	
	private static void addAllMethodBindings(List<BindingTuple> list, TypeBinding binding, Set<String> banList, char[] fieldName, ASTNode responsible) {
		banList.addAll(METHODS_IN_OBJECT);
		addAllMethodBindings0(list, binding, banList, fieldName, responsible);
	}
	
	private static void addAllMethodBindings0(List<BindingTuple> list, TypeBinding binding, Set<String> banList, char[] fieldName, ASTNode responsible) {
		if (binding == null) return;
		
		TypeBinding inner;
		
		if (binding instanceof ParameterizedTypeBinding) {
			inner = ((ParameterizedTypeBinding) binding).genericType();
		} else {
			inner = binding;
		}
		
		if (inner instanceof SourceTypeBinding) {
			ClassScope cs = ((SourceTypeBinding)inner).scope;
			if (cs != null) {
				try {
					Reflection.classScopeBuildFieldsAndMethodsMethod.invoke(cs);
				} catch (Exception e) {
					// See 'Reflection' class for why we ignore this exception.
				}
			}
		}
		
		if (binding instanceof ReferenceBinding) {
			ReferenceBinding rb = (ReferenceBinding) binding;
			MethodBinding[] parameterizedSigs = rb.availableMethods();
			MethodBinding[] baseSigs = parameterizedSigs;
			if (binding instanceof ParameterizedTypeBinding) {
				baseSigs = ((ParameterizedTypeBinding)binding).genericType().availableMethods();
				if (baseSigs.length != parameterizedSigs.length) {
					// The last known state of eclipse source says this can't happen, so we rely on it,
					// but if this invariant is broken, better to go with 'arg0' naming instead of crashing.
					baseSigs = parameterizedSigs;
				}
			}
			for (int i = 0; i < parameterizedSigs.length; i++) {
				MethodBinding mb = parameterizedSigs[i];
				String sig = printSig(mb);
				if (mb.isStatic()) continue;
				if (mb.isBridge()) continue;
				if (mb.isConstructor()) continue;
				if (mb.isDefaultAbstract()) continue;
				if (!mb.isPublic()) continue;
				if (mb.isSynthetic()) continue;
				if (!banList.add(sig)) continue; // If add returns false, it was already in there.
				BindingTuple pair = new BindingTuple(mb, baseSigs[i], fieldName, responsible);
				list.add(pair);
			}
			addAllMethodBindings0(list, rb.superclass(), banList, fieldName, responsible);
			ReferenceBinding[] interfaces = rb.superInterfaces();
			if (interfaces != null) {
				for (ReferenceBinding iface : interfaces) addAllMethodBindings0(list, iface, banList, fieldName, responsible);
			}
		}
	}
	
	private static final class BindingTuple {
		BindingTuple(MethodBinding parameterized, MethodBinding base, char[] fieldName, ASTNode responsible) {
			this.parameterized = parameterized;
			this.base = base;
			this.fieldName = fieldName;
			this.responsible = responsible;
		}
		
		final MethodBinding parameterized, base;
		final char[] fieldName;
		final ASTNode responsible;
		
		@Override public String toString() {
			return String.format("{param: %s, base: %s, fieldName: %s}", parameterized == null ? "(null)" : printSig(parameterized), base == null ? "(null)" : printSig(base), new String(fieldName));
		}
	}
	
	private static final List<String> METHODS_IN_OBJECT = Collections.unmodifiableList(Arrays.asList(
			"hashCode()",
			"canEqual(java.lang.Object)",  //Not in j.l.Object, but it goes with hashCode and equals so if we ignore those two, we should ignore this one.
			"equals(java.lang.Object)",
			"wait()",
			"wait(long)",
			"wait(long, int)",
			"notify()",
			"notifyAll()",
			"toString()",
			"getClass()",
			"clone()",
			"finalize()"));
	
	private static String printSig(MethodBinding binding) {
		StringBuilder signature = new StringBuilder();
		
		signature.append(binding.selector);
		signature.append("(");
		boolean first = true;
		if (binding.parameters != null) for (TypeBinding param : binding.parameters) {
			if (!first) signature.append(", ");
			first = false;
			signature.append(typeBindingToSignature(param));
		}
		signature.append(")");
		
		return signature.toString();
	}
	
	private static String typeBindingToSignature(TypeBinding binding) {
		binding = binding.erasure();
		if (binding != null && binding.isBaseType()) {
			return new String (binding.sourceName());
		} else if (binding instanceof ReferenceBinding) {
			String pkg = binding.qualifiedPackageName() == null ? "" : new String(binding.qualifiedPackageName());
			String qsn = binding.qualifiedSourceName() == null ? "" : new String(binding.qualifiedSourceName());
			return pkg.isEmpty() ? qsn : (pkg + "." + qsn);
		} else if (binding instanceof ArrayBinding) {
			StringBuilder out = new StringBuilder();
			out.append(typeBindingToSignature(binding.leafComponentType()));
			for (int i = 0; i < binding.dimensions(); i++) out.append("[]");
			return out.toString();
		}
		
		return "";
	}
	
	private static boolean charArrayEquals(String s, char[] c) {
		if (s == null) return c == null;
		if (c == null) return false;
		
		if (s.length() != c.length) return false;
		for (int i = 0; i < s.length(); i++) if (s.charAt(i) != c[i]) return false;
		return true;
		
		
	}
}
