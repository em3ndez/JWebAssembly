/*
 * Copyright 2023 Volker Berlin (i-net software)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.inetsoftware.jwebassembly.module;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import de.inetsoftware.classparser.FieldInfo;
import de.inetsoftware.jwebassembly.WasmException;
import de.inetsoftware.jwebassembly.module.StackInspector.StackValue;
import de.inetsoftware.jwebassembly.module.WasmInstruction.Type;
import de.inetsoftware.jwebassembly.wasm.AnyType;
import de.inetsoftware.jwebassembly.wasm.NamedStorageType;
import de.inetsoftware.jwebassembly.wasm.ValueType;
import de.inetsoftware.jwebassembly.wasm.VariableOperator;

/**
 * Replace Unsafe operations with simpler WASM operations which does not need reflections.
 * 
 * In Java a typical Unsafe code look like:
 * 
 * <pre>
 * <code>
 * private static final Unsafe UNSAFE = Unsafe.getUnsafe();
 * private static final long FIELD_OFFSET = UNSAFE.objectFieldOffset(Foobar.class.getDeclaredField("field"));
 * 
 * ...
 * 
 * UNSAFE.compareAndSwapInt(this, FIELD_OFFSET, expect, update);
 * </code>
 * </pre>
 * 
 * Because WASM does not support reflection the native code of UNSAFE can't simple replaced. That this manager convert
 * this to the follow pseudo code in WASM:
 * 
 * <pre>
 * <code>
 * Foobar..compareAndSwapInt(this, FIELD_OFFSET, expect, update);
 * 
 * ...
 * 
 * boolean .compareAndSwapInt(Object obj, long fieldOffset, int expect, int update ) {
 *     if( obj.field == expect ) {
 *         obj.field = update;
 *         return true;
 *     }
 *     return false;
 * }
 * </code>
 * </pre>
 * 
 * @author Volker Berlin
 */
class UnsafeManager {

    /** Unsafe class bane in Java 8 */
    static final String                              UNSAFE_8  = "sun/misc/Unsafe";

    /** Unsafe class bane in Java 11 */
    static final String                              UNSAFE_11 = "jdk/internal/misc/Unsafe";

    /** Wrapper for Unsafe */
    static final String                              FIELDUPDATER = "java/util/concurrent/atomic/AtomicReferenceFieldUpdater";

    /** VARHANDLE as modern replacement of Unsafe */
    static final String                              VARHANDLE = "java/lang/invoke/VarHandle";

    static final String                              METHOD_HANDLES = "java/lang/invoke/MethodHandles$Lookup";

    @Nonnull
    private final FunctionManager                    functions;

    @Nonnull
    private final TypeManager                        types;

    @Nonnull
    private final ClassFileLoader                    classFileLoader;

    @Nonnull
    private final HashMap<FunctionName, UnsafeState> unsafes      = new HashMap<>();

    @Nonnull
    private final HashMap<Integer, UnsafeState>      localStates  = new HashMap<>();

    /**
     * Create an instance of the manager
     * 
     * @param options
     *            compiler option/properties
     * @param classFileLoader
     *            for loading the class files
     */
    UnsafeManager( @Nonnull WasmOptions options, @Nonnull ClassFileLoader classFileLoader ) {
        this.functions = options.functions;
        this.types = options.types;
        this.classFileLoader = classFileLoader;
    }

    /**
     * Replace any Unsafe API call with direct field access.
     * 
     * @param instructions
     *            the instruction list of a function/method
     * @throws IOException
     *             If any I/O error occur
     */
    void replaceUnsafe( @Nonnull List<WasmInstruction> instructions ) throws IOException {
        // search for Unsafe function calls
        localStates.clear();
        for( int i = 0; i < instructions.size(); i++ ) {
            WasmInstruction instr = instructions.get( i );
            switch( instr.getType() ) {
                case CallVirtual:
                case Call:
                    WasmCallInstruction callInst = (WasmCallInstruction)instr;
                    switch( callInst.getFunctionName().className ) {
                        case UNSAFE_8:
                        case UNSAFE_11:
                        case FIELDUPDATER:
                            patch( instructions, i, callInst );
                            break;
                        case VARHANDLE:
                        case METHOD_HANDLES:
                            patchVarHandle( instructions, i, callInst );
                            break;
                    }
                    break;
                default:
            }
        }
    }

    /**
     * Patch in the instruction list an Unsafe method call. It does not change the count of instructions.
     * 
     * @param instructions
     *            the instruction list of a function/method
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @throws IOException
     *             If any I/O error occur
     */
    private void patch( @Nonnull List<WasmInstruction> instructions, int idx, @Nonnull WasmCallInstruction callInst ) throws IOException {
        FunctionName name = callInst.getFunctionName();
        switch( name.signatureName ) {
            case "sun/misc/Unsafe.getUnsafe()Lsun/misc/Unsafe;":
            case "jdk/internal/misc/Unsafe.getUnsafe()Ljdk/internal/misc/Unsafe;":
                patch_getUnsafe( instructions, idx );
                break;
            case "sun/misc/Unsafe.objectFieldOffset(Ljava/lang/reflect/Field;)J":
            case "jdk/internal/misc/Unsafe.objectFieldOffset(Ljava/lang/reflect/Field;)J":
                patch_objectFieldOffset_Java8( instructions, idx, callInst );
                break;
            case "jdk/internal/misc/Unsafe.objectFieldOffset(Ljava/lang/Class;Ljava/lang/String;)J":
                patch_objectFieldOffset_Java11( instructions, idx, callInst, false );
                break;
            case "java/util/concurrent/atomic/AtomicReferenceFieldUpdater.newUpdater(Ljava/lang/Class;Ljava/lang/Class;Ljava/lang/String;)Ljava/util/concurrent/atomic/AtomicReferenceFieldUpdater;":
                patch_objectFieldOffset_Java11( instructions, idx, callInst, true );
                break;
            case "sun/misc/Unsafe.arrayBaseOffset(Ljava/lang/Class;)I":
            case "jdk/internal/misc/Unsafe.arrayBaseOffset(Ljava/lang/Class;)I":
                patch_arrayBaseOffset( instructions, idx, callInst );
                break;
            case "sun/misc/Unsafe.arrayIndexScale(Ljava/lang/Class;)I":
            case "jdk/internal/misc/Unsafe.arrayIndexScale(Ljava/lang/Class;)I":
                patch_arrayIndexScale( instructions, idx, callInst );
                break;
            case "sun/misc/Unsafe.getObjectVolatile(Ljava/lang/Object;J)Ljava/lang/Object;":
            case "sun/misc/Unsafe.getInt(Ljava/lang/Object;J)I":
            case "sun/misc/Unsafe.getLong(Ljava/lang/Object;J)J":
            case "jdk/internal/misc/Unsafe.getInt(Ljava/lang/Object;J)I":
            case "jdk/internal/misc/Unsafe.getLong(Ljava/lang/Object;J)J":
            case "jdk/internal/misc/Unsafe.getObject(Ljava/lang/Object;J)Ljava/lang/Object;":
                patchFieldFunction( instructions, idx, callInst, name, 1 );
                break;
            case "sun/misc/Unsafe.getAndAddInt(Ljava/lang/Object;JI)I":
            case "sun/misc/Unsafe.getAndSetInt(Ljava/lang/Object;JI)I":
            case "sun/misc/Unsafe.putOrderedInt(Ljava/lang/Object;JI)V":
            case "sun/misc/Unsafe.putInt(Ljava/lang/Object;JI)V":
            case "sun/misc/Unsafe.getAndAddLong(Ljava/lang/Object;JJ)J":
            case "sun/misc/Unsafe.getAndSetLong(Ljava/lang/Object;JJ)J":
            case "sun/misc/Unsafe.putOrderedLong(Ljava/lang/Object;JJ)V":
            case "sun/misc/Unsafe.putLong(Ljava/lang/Object;JJ)V":
            case "sun/misc/Unsafe.putOrderedObject(Ljava/lang/Object;JLjava/lang/Object;)V":
            case "sun/misc/Unsafe.putObjectVolatile(Ljava/lang/Object;JLjava/lang/Object;)V":
            case "sun/misc/Unsafe.putObject(Ljava/lang/Object;JLjava/lang/Object;)V":
            case "sun/misc/Unsafe.getAndSetObject(Ljava/lang/Object;JLjava/lang/Object;)Ljava/lang/Object;":
            case "jdk/internal/misc/Unsafe.getAndAddInt(Ljava/lang/Object;JI)I":
            case "jdk/internal/misc/Unsafe.getAndSetInt(Ljava/lang/Object;JI)I":
            case "jdk/internal/misc/Unsafe.putIntRelease(Ljava/lang/Object;JI)V":
            case "jdk/internal/misc/Unsafe.putInt(Ljava/lang/Object;JI)V":
            case "jdk/internal/misc/Unsafe.getAndAddLong(Ljava/lang/Object;JJ)J":
            case "jdk/internal/misc/Unsafe.getAndSetLong(Ljava/lang/Object;JJ)J":
            case "jdk/internal/misc/Unsafe.putLongRelease(Ljava/lang/Object;JJ)V":
            case "jdk/internal/misc/Unsafe.putLongVolatile(Ljava/lang/Object;JJ)V":
            case "jdk/internal/misc/Unsafe.putLong(Ljava/lang/Object;JJ)V":
            case "jdk/internal/misc/Unsafe.putObject(Ljava/lang/Object;JLjava/lang/Object;)V":
            case "jdk/internal/misc/Unsafe.getObjectAcquire(Ljava/lang/Object;J)Ljava/lang/Object;":
            case "jdk/internal/misc/Unsafe.putObjectRelease(Ljava/lang/Object;JLjava/lang/Object;)V":
                patchFieldFunction( instructions, idx, callInst, name, 2 );
                break;
            case "sun/misc/Unsafe.compareAndSwapInt(Ljava/lang/Object;JII)Z":
            case "sun/misc/Unsafe.compareAndSwapLong(Ljava/lang/Object;JJJ)Z":
            case "sun/misc/Unsafe.compareAndSwapObject(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z":
            case "jdk/internal/misc/Unsafe.compareAndSetInt(Ljava/lang/Object;JII)Z":
            case "jdk/internal/misc/Unsafe.compareAndSetLong(Ljava/lang/Object;JJJ)Z":
            case "jdk/internal/misc/Unsafe.compareAndSetObject(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z":
                patchFieldFunction( instructions, idx, callInst, name, 3 );
                break;
            case "java/util/concurrent/atomic/AtomicReferenceFieldUpdater.compareAndSet(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z":
                patchFieldFunction( instructions, idx, callInst, name, 4 );
                break;
            case "jdk/internal/misc/Unsafe.getCharUnaligned(Ljava/lang/Object;JZ)C":
            case "jdk/internal/misc/Unsafe.getShortUnaligned(Ljava/lang/Object;JZ)S":
            case "jdk/internal/misc/Unsafe.getIntUnaligned(Ljava/lang/Object;J)I":
            case "jdk/internal/misc/Unsafe.getIntUnaligned(Ljava/lang/Object;JZ)I":
            case "jdk/internal/misc/Unsafe.getLongUnaligned(Ljava/lang/Object;J)J":
            case "jdk/internal/misc/Unsafe.getLongUnaligned(Ljava/lang/Object;JZ)J":
                patch_getLongUnaligned( instructions, idx, callInst, name );
                break;
            case "jdk/internal/misc/Unsafe.isBigEndian()Z":
                patch_isBigEndian( instructions, idx, callInst );
                break;
            case "jdk/internal/misc/Unsafe.shouldBeInitialized(Ljava/lang/Class;)Z":
                replaceWithConstNumber( instructions, idx, callInst, 2, 0 );
                break;
            case "jdk/internal/misc/Unsafe.storeFence()V":
                remove( instructions, idx, callInst, 1 );
                break;
            case "jdk/internal/misc/Unsafe.ensureClassInitialized(Ljava/lang/Class;)V":
            case "jdk/internal/misc/Unsafe.unpark(Ljava/lang/Object;)V":
            case "sun/misc/Unsafe.unpark(Ljava/lang/Object;)V":
                remove( instructions, idx, callInst, 2 );
                break;
            case "sun/misc/Unsafe.park(ZJ)V":
            case "jdk/internal/misc/Unsafe.park(ZJ)V":
                remove( instructions, idx, callInst, 3 );
                break;
            default:
                throw new WasmException( "Unsupported Unsafe method: " + name.signatureName, -1 );
        }
    }

    /**
     * Replace a call to Unsafe.getUnsafe() with a NOP operation.
     * 
     * @param instructions
     *            the instruction list of a function/method
     * @param idx
     *            the index in the instructions
     */
    private void patch_getUnsafe( @Nonnull List<WasmInstruction> instructions, int idx ) {
        WasmInstruction instr = instructions.get( idx + 1 );

        int to = idx + (instr.getType() == Type.Global ? 2 : 1);

        nop( instructions, idx, to );
    }

    /**
     * Find the field on which the offset is assign: long FIELD_OFFSET = UNSAFE.objectFieldOffset(...
     * 
     * @param instructions
     *            the instruction list of a function/method
     * @param idx
     *            the index in the instructions
     * @return the state
     */
    @Nonnull
    private UnsafeState findUnsafeState( @Nonnull List<WasmInstruction> instructions, int idx ) {
        // find the field on which the offset is assign: long FIELD_OFFSET = UNSAFE.objectFieldOffset(...
        WasmInstruction instr;
        idx++;
        INSTR: do {
            instr = instructions.get( idx );
            switch( instr.getType() ) {
                case Convert:
                    idx++;
                    continue INSTR;
                case Global:
                    break;
                case Jump:
                    int pos = ((JumpInstruction)instr).getJumpPosition();
                    for( idx++; idx < instructions.size(); idx++) {
                        instr = instructions.get( idx );
                        if( instr.getCodePosition() >= pos ) {
                            break;
                        }
                    }
                    continue INSTR;
                case Local:
                    // occur with jdk.internal.misc.InnocuousThread
                    UnsafeState state = new UnsafeState();
                    localStates.put( ((WasmLocalInstruction)instr).getIndex(), state );
                    return state;
                default:
                    throw new WasmException( "Unsupported assign operation for Unsafe field offset: " + instr.getType(), -1 );
            }
            break;
        } while( true );
        FunctionName fieldNameWithOffset = ((WasmGlobalInstruction)instr).getFieldName();
        UnsafeState state = unsafes.get( fieldNameWithOffset );
        if( state == null ) {
            unsafes.put( fieldNameWithOffset, state = new UnsafeState() );
        }
        return state;
    }

    /**
     * Get the class name from the stack value. It is searching a WasmConstClassInstruction that produce the value of
     * the stack value.
     * 
     * @param instructions
     *            the instruction list of a function/method
     * @param stackValue
     *            the stack value (instruction and position that produce an stack value)
     * @return the class name like: java/lang/String
     */
    @Nonnull
    private static String getClassConst( List<WasmInstruction> instructions, StackValue stackValue ) {
        WasmInstruction instr = stackValue.instr;
        switch( instr.getType() ) {
            case Local:
                int slot = ((WasmLocalInstruction)instr).getSlot();
                for( int i = stackValue.idx - 1; i >= 0; i-- ) {
                    instr = instructions.get( i );
                    if( instr.getType() == Type.Local ) {
                        WasmLocalInstruction loadInstr = (WasmLocalInstruction)instr;
                        if( loadInstr.getSlot() == slot && loadInstr.getOperator() == VariableOperator.set ) {
                            stackValue = StackInspector.findInstructionThatPushValue( instructions.subList( 0, i ), 1, instr.getCodePosition() );
                            instr = stackValue.instr;
                            break;
                        }

                    }
                }
                break;
            default:
        }
        return ((WasmConstClassInstruction)instr).getValue();
    }

    /**
     * Patch a method call to Unsafe.objectFieldOffset() and find the parameter for other patch operations.
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @throws IOException
     *             If any I/O error occur
     */
    private void patch_objectFieldOffset_Java8( List<WasmInstruction> instructions, int idx, WasmCallInstruction callInst ) throws IOException {
        UnsafeState state = findUnsafeState( instructions, idx );

        // objectFieldOffset() has 2 parameters THIS(Unsafe) and a Field
        List<WasmInstruction> paramInstructions = instructions.subList( 0, idx );
        int from = StackInspector.findInstructionThatPushValue( paramInstructions, 2, callInst.getCodePosition() ).idx;

        StackValue stackValue = StackInspector.findInstructionThatPushValue( paramInstructions, 1, callInst.getCodePosition() );
        WasmInstruction instr = stackValue.instr;
        WasmCallInstruction fieldInst = (WasmCallInstruction)instr;

        FunctionName fieldFuncName = fieldInst.getFunctionName();
        switch( fieldFuncName.signatureName ) {
            case "java/lang/Class.getDeclaredField(Ljava/lang/String;)Ljava/lang/reflect/Field;":
                stackValue = StackInspector.findInstructionThatPushValue( instructions.subList( 0, stackValue.idx ), 1, fieldInst.getCodePosition() );
                state.fieldName = ((WasmConstStringInstruction)stackValue.instr).getValue();

                // find the class value on which getDeclaredField is called
                stackValue = StackInspector.findInstructionThatPushValue( instructions.subList( 0, stackValue.idx ), 1, fieldInst.getCodePosition() );
                state.typeName = getClassConst( instructions, stackValue );
                break;

            default:
                throw new WasmException( "Unsupported Unsafe method to get target field: " + fieldFuncName.signatureName, -1 );
        }

        useFieldName( state );
        nop( instructions, from, idx + 2 );
    }

    /**
     * Patch a method call to Unsafe.objectFieldOffset() and find the parameter for other patch operations.
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @param isAtomicReferenceFieldUpdater
     *            true, if is AtomicReferenceFieldUpdater
     * @throws IOException
     *             If any I/O error occur
     */
    private void patch_objectFieldOffset_Java11( List<WasmInstruction> instructions, int idx, WasmCallInstruction callInst, boolean isAtomicReferenceFieldUpdater ) throws IOException {
        UnsafeState state = findUnsafeState( instructions, idx );

        // objectFieldOffset() has 3 parameters THIS(Unsafe), class and the fieldname
        List<WasmInstruction> paramInstructions = instructions.subList( 0, idx );
        int from = StackInspector.findInstructionThatPushValue( paramInstructions, 3, callInst.getCodePosition() ).idx;

        StackValue stackValue = StackInspector.findInstructionThatPushValue( paramInstructions, 1, callInst.getCodePosition() );
        state.fieldName = ((WasmConstStringInstruction)stackValue.instr).getValue();

        // find the class value on which getDeclaredField is called
        int classParamIdx = isAtomicReferenceFieldUpdater ? 3 : 2;
        stackValue = StackInspector.findInstructionThatPushValue( paramInstructions, classParamIdx, callInst.getCodePosition() );
        state.typeName = getClassConst( instructions, stackValue );

        useFieldName( state );
        nop( instructions, from, idx + 2 );
    }

    /**
     * Patch a method call to Unsafe.arrayBaseOffset() and find the parameter for other patch operations.
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     */
    private void patch_arrayBaseOffset( List<WasmInstruction> instructions, int idx, WasmCallInstruction callInst ) {
        UnsafeState state = findUnsafeState( instructions, idx );

        // objectFieldOffset() has 2 parameters THIS(Unsafe) and a Class from an array
        List<WasmInstruction> paramInstructions = instructions.subList( 0, idx );
        int from = StackInspector.findInstructionThatPushValue( paramInstructions, 2, callInst.getCodePosition() ).idx;

        StackValue stackValue = StackInspector.findInstructionThatPushValue( paramInstructions, 1, callInst.getCodePosition() );
        state.typeName = getClassConst( instructions, stackValue );

        nop( instructions, from, idx );
        // we put the constant value 0 on the stack, we does not need array base offset in WASM
        instructions.set( idx, new WasmConstNumberInstruction( 0, callInst.getCodePosition(), callInst.getLineNumber() ) );
    }

    /**
     * Patch method call to Unsafe.arrayIndexScale()
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     */
    private void patch_arrayIndexScale( List<WasmInstruction> instructions, int idx, WasmCallInstruction callInst ) {
        int from = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), 2, callInst.getCodePosition() ).idx;

        nop( instructions, from, idx );
        // we put the constant value 1 on the stack because we does not want shift array positions
        instructions.set( idx, new WasmConstNumberInstruction( 1, callInst.getCodePosition(), callInst.getLineNumber() ) );
    }

    /**
     * Mark the field as used
     * 
     * @param state
     *            the state
     * @throws IOException
     *             If any I/O error occur
     */
    private void useFieldName( @Nonnull UnsafeState state ) throws IOException {
        FieldInfo fieldInfo = classFileLoader.get( state.typeName ).getField( state.fieldName );
        NamedStorageType fieldName = new NamedStorageType( state.typeName, fieldInfo, types );
        types.valueOf( state.typeName ).useFieldName( fieldName );
    }

    /**
     * Patch an unsafe function that access a field
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @param name
     *            the calling function
     * @param fieldNameParam
     *            the function parameter on the stack with the field offset on the stack. This must be a long (Java signature "J") for Unsafe. This is the parameter count from right.
     */
    private void patchFieldFunction( List<WasmInstruction> instructions, int idx, final WasmCallInstruction callInst, FunctionName name, int fieldNameParam ) {
        StackValue stackValue = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), fieldNameParam, callInst.getCodePosition() );
        WasmInstruction instr = stackValue.instr;

        Set<FunctionName> fieldNames;
        FunctionName fieldNameWithOffset = null;
        UnsafeState state = null;
        if( instr.getType() == Type.Global ) {
            fieldNameWithOffset = ((WasmGlobalInstruction)instr).getFieldName();
            fieldNames = Collections.singleton( fieldNameWithOffset );
        } else {
            fieldNames = new HashSet<>();
            if( instr.getType() == Type.Local ) {
                // occur with jdk.internal.misc.InnocuousThread
                state = localStates.get(  ((WasmLocalInstruction)instr).getIndex() );
                if( state != null ) {
                    fieldNameWithOffset = new FunctionName( state.typeName, state.fieldName, "" );
                }
            }
            if( fieldNameWithOffset == null ) {
                // java.util.concurrent.ConcurrentHashMap.tabAt() calculate a value with the field
                int pos2 = stackValue.idx;
                stackValue = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), fieldNameParam + 1, callInst.getCodePosition() );
                int i = stackValue.idx;
                for( ; i < pos2; i++ ) {
                    instr = instructions.get( i );
                    if( instr.getType() != Type.Global ) {
                        continue;
                    }
                    fieldNameWithOffset = ((WasmGlobalInstruction)instr).getFieldName();
                    fieldNames.add( fieldNameWithOffset );
                }
            }
        }

        UnsafeState state_ = state;

        WatCodeSyntheticFunctionName func =
                        new WatCodeSyntheticFunctionName( fieldNameWithOffset.className, '.' + fieldNameWithOffset.methodName + '.' + name.methodName, name.signature, "", (AnyType[])null ) {
                            @Override
                            protected String getCode() {
                                UnsafeState state = state_;
                                for(FunctionName fieldNameWithOffset : fieldNames ) {
                                    if( state != null ) {
                                        break;
                                    }
                                    state = unsafes.get( fieldNameWithOffset );
                                }
                                if( state == null ) {
                                    if( functions.isFinish() ) {
                                        throw new RuntimeException( this.fullName + name.signature );
                                    }
                                    // we are in the scan phase. The static code was not scanned yet.
                                    return "";
                                }
                                AnyType[] paramTypes = callInst.getPopValueTypes();
                                switch( name.methodName ) {
                                    case "compareAndSwapInt":
                                    case "compareAndSetInt":
                                    case "compareAndSwapLong":
                                    case "compareAndSetLong":
                                    case "compareAndSwapObject":
                                    case "compareAndSetObject":
                                    case "compareAndSet": // AtomicReferenceFieldUpdater
                                        AnyType type = paramTypes[3];
                                        if( type.isRefType() ) {
                                            type = ValueType.ref;
                                        }
                                        if( state.fieldName != null ) {
                                            // field access
                                            int paramOffset = "java/util/concurrent/atomic/AtomicReferenceFieldUpdater".equals( name.className ) ? -1 : 0;
                                            return "local.get 1" // THIS
                                                            + " struct.get " + state.typeName + ' ' + state.fieldName //
                                                            + " local.get " + (3 + paramOffset) // expected
                                                            + " " + type + ".eq" //
                                                            + " if" //
                                                            + "   local.get 1" // THIS
                                                            + "   local.get " + (4 + paramOffset) // update
                                                            + "   struct.set " + state.typeName + ' ' + state.fieldName //
                                                            + "   i32.const 1" //
                                                            + "   return" //
                                                            + " end" //
                                                            + " i32.const 1" //
                                                            + " return";
                                        } else {
                                            // array access
                                            return "local.get 1" // THIS
                                                            + " local.get 2" // the array index
                                                            + " i32.wrap_i64" // long -> int
                                                            + " array.get " + state.typeName //
                                                            + " local.get 3 " // expected
                                                            + " " + type + ".eq" //
                                                            + " if" //
                                                            + "   local.get 1" // THIS
                                                            + "   local.get 2" // the array index
                                                            + "   i32.wrap_i64" // long -> int
                                                            + "   local.get 4 " // update
                                                            + "   array.set " + state.typeName //
                                                            + "   i32.const 1" //
                                                            + "   return" //
                                                            + " end" //
                                                            + " i32.const 1" //
                                                            + " return";
                                        }

                                    case "getAndAddInt":
                                    case "getAndAddLong":
                                        return "local.get 1" // THIS
                                                        + " local.get 1" // THIS
                                                        + " struct.get " + state.typeName + ' ' + state.fieldName //
                                                        + " local.tee 4" // temp
                                                        + " local.get 3 " // delta
                                                        + paramTypes[3] + ".add" //
                                                        + " struct.set " + state.typeName + ' ' + state.fieldName //
                                                        + " local.get 4" // temp
                                                        + " return";

                                    case "getAndSetInt":
                                    case "getAndSetLong":
                                    case "getAndSetObject":
                                        return "local.get 1" // THIS
                                                        + " struct.get " + state.typeName + ' ' + state.fieldName //
                                                        + " local.get 1" // THIS
                                                        + " local.get 3" // newValue
                                                        + " struct.set " + state.typeName + ' ' + state.fieldName //
                                                        + " return";

                                    case "putOrderedInt":
                                    case "putInt":
                                    case "putOrderedLong":
                                    case "putLong":
                                    case "putLongVolatile":
                                    case "putOrderedObject":
                                    case "putObjectVolatile":
                                    case "putObject":
                                    case "putObjectRelease":
                                        if( state.fieldName != null ) {
                                            // field access
                                            return "local.get 1" // THIS
                                                            + " local.get 3" // x
                                                            + " struct.set " + state.typeName + ' ' + state.fieldName;
                                        } else {
                                            // array access
                                            return "local.get 1" // THIS
                                                            + " local.get 2" // the array index
                                                            + " i32.wrap_i64" // long -> int
                                                            + " local.get 3" // x
                                                            + " array.set " + state.typeName;
                                        }

                                    case "getInt":
                                    case "getLong":
                                    case "getObject":
                                    case "getObjectVolatile":
                                        if( state.fieldName != null ) {
                                            // field access
                                            return "local.get 1" // THIS
                                                            + " struct.get " + state.typeName + ' ' + state.fieldName //
                                                            + " return";
                                        } else {
                                            // array access
                                            return "local.get 1" // array
                                                            + " local.get 2" // the array index
                                                            + " i32.wrap_i64" // long -> int
                                                            + " array.get " + state.typeName + " return";
                                        }
                                }

                                throw new RuntimeException( name.signatureName );
                            }
                        };
        boolean needThisParameter = true;
        functions.markAsNeeded( func, needThisParameter ); // original function has an THIS parameter of the Unsafe instance, we need to consume it
        WasmCallInstruction call = new WasmCallInstruction( func, callInst.getCodePosition(), callInst.getLineNumber(), callInst.getTypeManager(), needThisParameter );
        instructions.set( idx, call );

        // a virtual method call has also a DUP of this because we need for virtual method dispatch the parameter 2 times.
        for( int i = idx; i >= 0; i-- ) {
            instr = instructions.get( i );
            if( instr.getType() == Type.DupThis && ((DupThis)instr).getValue() == callInst ) {
                nop( instructions, i, i + 1 );
                break;
            }
        }
    }

    /**
     * Patch an unsafe function that access a field
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     */
    private void patch_getLongUnaligned( List<WasmInstruction> instructions, int idx, final WasmCallInstruction callInst, FunctionName name ) {
        WatCodeSyntheticFunctionName func = new WatCodeSyntheticFunctionName( "", name.methodName, name.signature, "unreachable", (AnyType[])null );
        functions.markAsNeeded( func, false );
        WasmCallInstruction call = new WasmCallInstruction( func, callInst.getCodePosition(), callInst.getLineNumber(), callInst.getTypeManager(), false );
        instructions.set( idx, call );
    }

    /**
     * Patch an unsafe function that access a field
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     */
    private void patch_isBigEndian( List<WasmInstruction> instructions, int idx, final WasmCallInstruction callInst ) {
//        int from = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), 1, callInst.getCodePosition() ).idx;
//
//        nop( instructions, from, idx );

        // on x86 use little endian
        instructions.set( idx, new WasmConstNumberInstruction( 0, callInst.getCodePosition(), callInst.getLineNumber() ) );
    }

    /**
     * Patch an unsafe function that access a field
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     */
    private void replaceWithConstNumber( List<WasmInstruction> instructions, int idx, final WasmCallInstruction callInst, int paramCount, int number ) {
        int from = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), 1, callInst.getCodePosition() ).idx;

        nop( instructions, from, idx );

        // on x86 use little endian
        instructions.set( idx, new WasmConstNumberInstruction( number, callInst.getCodePosition(), callInst.getLineNumber() ) );
    }

    /**
     * Remove an unsafe function that access a field
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @param paramCount
     *            the count of params that must be removed from stack (including THIS if instance method)
     */
    private void remove( List<WasmInstruction> instructions, int idx, final WasmCallInstruction callInst, int paramCount ) {
        int from = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), paramCount, callInst.getCodePosition() ).idx;

        nop( instructions, from, idx + 1 );
    }

    /**
     * Replace the instructions with NOP operations
     * 
     * @param instructions
     *            the instruction list
     * @param from
     *            starting index
     * @param to
     *            end index
     */
    private void nop( List<WasmInstruction> instructions, int from, int to ) {
        for( int i = from; i < to; i++ ) {
            WasmInstruction instr = instructions.get( i );
            instructions.set( i, new WasmNopInstruction( instr.getCodePosition(), instr.getLineNumber() ) );
        }
    }

    private void patchVarHandle( List<WasmInstruction> instructions, int idx, WasmCallInstruction callInst ) throws IOException {
        FunctionName name = callInst.getFunctionName();
        switch( name.methodName ) {
            case "findVarHandle":
                patch_findVarHandle( instructions, idx, callInst );
                break;
            case "arrayElementVarHandle": // java/lang/invoke/MethodHandles
                UnsafeState state = findUnsafeState( instructions, idx );
                int from = StackInspector.findInstructionThatPushValue( instructions, 2, callInst.getCodePosition() ).idx;

                StackValue stackValue = StackInspector.findInstructionThatPushValue( instructions, 1, callInst.getCodePosition() );
                state.typeName = getClassConst( instructions, stackValue );
                break;
            case "getAndSet":
            case "set":
            case "getAcquire":
            case "getAndAdd":
            case "getAndBitwiseOr":
            case "get":
            case "compareAndSet":
            case "weakCompareAndSet":
            case "setVolatile":
            case "setRelease":
            case "setOpaque":
                patchVarHandleFieldFunction( instructions, idx, callInst, name, callInst.getPopCount() );
                break;
            case "releaseFence":
                nop( instructions, idx, idx + 1 );
                break;
            default:
                throw new WasmException( "Unsupported VarHandle method: " + name.signatureName, -1 );
        }
    }

    /**
     * Patch a method call to Unsafe.objectFieldOffset() and find the parameter for other patch operations.
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @throws IOException
     *             If any I/O error occur
     */
    private void patch_findVarHandle( List<WasmInstruction> instructions, int idx, WasmCallInstruction callInst ) throws IOException {
        UnsafeState state = findUnsafeState( instructions, idx );

        // objectFieldOffset() has 3 parameters THIS(Unsafe), class and the fieldname
        List<WasmInstruction> paramInstructions = instructions.subList( 0, idx );
        int from = StackInspector.findInstructionThatPushValue( paramInstructions, 4, callInst.getCodePosition() ).idx;

        StackValue stackValue = StackInspector.findInstructionThatPushValue( paramInstructions, 2, callInst.getCodePosition() );
        state.fieldName = ((WasmConstStringInstruction)stackValue.instr).getValue();

        // find the class value on which getDeclaredField is called
        stackValue = StackInspector.findInstructionThatPushValue( paramInstructions, 3, callInst.getCodePosition() );
        state.typeName = getClassConst( instructions, stackValue );

        useFieldName( state );
        nop( instructions, from, idx + 2 );
    }

    /**
     * Patch an unsafe function that access a field
     * 
     * @param instructions
     *            the instruction list
     * @param idx
     *            the index in the instructions
     * @param callInst
     *            the method call to Unsafe
     * @param name
     *            the calling function
     * @param fieldNameParam
     *            the function parameter with the field offset on the stack. This must be a long (Java signature "J") for Unsafe.
     */
    private void patchVarHandleFieldFunction( List<WasmInstruction> instructions, int idx, final WasmCallInstruction callInst, FunctionName name, int fieldNameParam ) {
        StackValue stackValue = StackInspector.findInstructionThatPushValue( instructions.subList( 0, idx ), fieldNameParam, callInst.getCodePosition() );
        FunctionName fieldNameWithOffset = ((WasmGlobalInstruction)stackValue.instr).getFieldName();
        WatCodeSyntheticFunctionName func =
                        new WatCodeSyntheticFunctionName( fieldNameWithOffset.className, '.' + name.methodName, name.signature, "", (AnyType[])null ) {
                            @Override
                            protected String getCode() {
                                UnsafeState state = unsafes.get( fieldNameWithOffset );
                                if( state == null ) {
                                    // we are in the scan phase. The static code was not scanned yet.
                                    return "";
                                }
                                AnyType[] paramTypes = callInst.getPopValueTypes();
                                switch( name.methodName ) {
                                    case "compareAndSet":
                                    case "weakCompareAndSet":
                                        AnyType type = paramTypes[3];
                                        if( type.isRefType() ) {
                                            type = ValueType.ref;
                                        }
                                        return "local.get 1" // THIS
                                                        + " struct.get " + state.typeName + ' ' + state.fieldName //
                                                        + " local.get 2 " // expected
                                                        + " " + type + ".eq" //
                                                        + " if" //
                                                        + "   local.get 1" // THIS
                                                        + "   local.get 3 " // update
                                                        + "   struct.set " + state.typeName + ' ' + state.fieldName //
                                                        + "   i32.const 1" //
                                                        + "   return" //
                                                        + " end" //
                                                        + " i32.const 1" //
                                                        + " return";

                                    case "getAndSet":
                                        return "local.get 1" // THIS
                                                        + " struct.get " + state.typeName + ' ' + state.fieldName //
                                                        + " local.get 1" // THIS
                                                        + " local.get 2" // newValue
                                                        + " struct.set " + state.typeName + ' ' + state.fieldName //
                                                        + " return";

                                    case "set":
                                        return "local.get 1" // THIS
                                                        + " local.get 2" // x
                                                        + " struct.set " + state.typeName + ' ' + state.fieldName;
                                }

                                throw new RuntimeException( name.signatureName );
                            }
                        };
        functions.markAsNeeded( func, false );
        WasmCallInstruction call = new WasmCallInstruction( func, callInst.getCodePosition(), callInst.getLineNumber(), callInst.getTypeManager(), false );
        instructions.set( idx, call );

        // a virtual method call has also a DUP of this because we need for virtual method dispatch the parameter 2 times.
        for( int i = idx; i >= 0; i-- ) {
            WasmInstruction instr = instructions.get( i );
            if( instr.getType() == Type.DupThis && ((DupThis)instr).getValue() == callInst ) {
                nop( instructions, i, i + 1 );
                break;
            }
        }
    }

    /**
     * Hold the state from declaring of Unsafe address
     */
    static class UnsafeState {
        String fieldName;

        String typeName;
    }
}
