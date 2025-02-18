/*
 * Copyright (c) 2017, 2024, Oracle and/or its affiliates.
 * Copyright (c) 2014, Regents of the University of California
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.oracle.graal.python.builtins.objects.foreign;

import static com.oracle.graal.python.builtins.objects.str.StringUtils.simpleTruffleStringFormatUncached;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___AND__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___DIVMOD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___EQ__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___FLOORDIV__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___GE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___GT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___INDEX__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___LE__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___LT__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___OR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___RAND__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___RDIVMOD__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___REPR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___RFLOORDIV__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___ROR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___RSUB__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___RTRUEDIV__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___RXOR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___STR__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___SUB__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___TRUEDIV__;
import static com.oracle.graal.python.nodes.SpecialMethodNames.J___XOR__;
import static com.oracle.graal.python.util.PythonUtils.TS_ENCODING;

import java.math.BigInteger;
import java.util.List;

import com.oracle.graal.python.annotations.Slot;
import com.oracle.graal.python.annotations.Slot.SlotKind;
import com.oracle.graal.python.builtins.Builtin;
import com.oracle.graal.python.builtins.CoreFunctions;
import com.oracle.graal.python.builtins.PythonBuiltinClassType;
import com.oracle.graal.python.builtins.PythonBuiltins;
import com.oracle.graal.python.builtins.objects.PNone;
import com.oracle.graal.python.builtins.objects.PNotImplemented;
import com.oracle.graal.python.builtins.objects.PythonAbstractObject;
import com.oracle.graal.python.builtins.objects.ints.PInt;
import com.oracle.graal.python.builtins.objects.object.ObjectNodes;
import com.oracle.graal.python.builtins.objects.type.SpecialMethodSlot;
import com.oracle.graal.python.builtins.objects.type.TpSlots;
import com.oracle.graal.python.builtins.objects.type.slots.TpSlotBinaryOp.BinaryOpBuiltinNode;
import com.oracle.graal.python.builtins.objects.type.slots.TpSlotInquiry.NbBoolBuiltinNode;
import com.oracle.graal.python.lib.PyNumberAddNode;
import com.oracle.graal.python.lib.PyNumberMultiplyNode;
import com.oracle.graal.python.lib.PyObjectStrAsTruffleStringNode;
import com.oracle.graal.python.nodes.PRaiseNode;
import com.oracle.graal.python.nodes.call.special.LookupAndCallBinaryNode;
import com.oracle.graal.python.nodes.expression.BinaryArithmetic;
import com.oracle.graal.python.nodes.expression.BinaryArithmetic.BitAndNode;
import com.oracle.graal.python.nodes.expression.BinaryArithmetic.BitOrNode;
import com.oracle.graal.python.nodes.expression.BinaryArithmetic.BitXorNode;
import com.oracle.graal.python.nodes.expression.BinaryOpNode;
import com.oracle.graal.python.nodes.function.PythonBuiltinBaseNode;
import com.oracle.graal.python.nodes.function.builtins.PythonBinaryBuiltinNode;
import com.oracle.graal.python.nodes.function.builtins.PythonUnaryBuiltinNode;
import com.oracle.graal.python.nodes.object.IsForeignObjectNode;
import com.oracle.graal.python.runtime.GilNode;
import com.oracle.graal.python.runtime.object.PythonObjectFactory;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Bind;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Cached.Shared;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateCached;
import com.oracle.truffle.api.dsl.GenerateInline;
import com.oracle.truffle.api.dsl.GenerateNodeFactory;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.NodeFactory;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.InlinedBranchProfile;
import com.oracle.truffle.api.strings.TruffleString;

/*
 * This class handles foreign numbers, whether they are integral or floating-point,
 * since interop has no message to know which one, and it would be impractical to handle
 * foreign integers in IntBuiltins for instance.
 * We are also currently handling foreign booleans here since Python bool inherits from int.
 *
 * NOTE: We are not using IndirectCallContext here in this file (except for CallNode)
 * because it seems unlikely that these interop messages would call back to Python
 * and that we would also need precise frame info for that case.
 * Adding it shouldn't hurt peak, but might be a non-trivial overhead in interpreter.
 */
@CoreFunctions(extendClasses = PythonBuiltinClassType.ForeignNumber)
public final class ForeignNumberBuiltins extends PythonBuiltins {
    public static TpSlots SLOTS = ForeignNumberBuiltinsSlotsGen.SLOTS;

    @Override
    protected List<? extends NodeFactory<? extends PythonBuiltinBaseNode>> getNodeFactories() {
        return ForeignNumberBuiltinsFactory.getFactories();
    }

    @Slot(SlotKind.nb_bool)
    @GenerateUncached
    @GenerateNodeFactory
    abstract static class BoolNode extends NbBoolBuiltinNode {
        @Specialization(limit = "getCallSiteInlineCacheMaxDepth()")
        static boolean bool(Object receiver,
                        @CachedLibrary("receiver") InteropLibrary lib,
                        @Cached GilNode gil) {
            gil.release(true);
            try {
                if (lib.isBoolean(receiver)) {
                    return lib.asBoolean(receiver);
                }
                if (lib.fitsInLong(receiver)) {
                    return lib.asLong(receiver) != 0;
                }
                if (lib.fitsInBigInteger(receiver)) {
                    return lib.asBigInteger(receiver).signum() != 0;
                }
                if (lib.fitsInDouble(receiver)) {
                    return lib.asDouble(receiver) != 0.0;
                }
                return false;
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } finally {
                gil.acquire();
            }
        }
    }

    @GenerateInline
    @GenerateCached(false)
    abstract static class NormalizeForeignForBinopNode extends Node {
        public abstract Object execute(Node inliningTarget, Object value);

        @Specialization(guards = "lib.isBoolean(obj)")
        Object doBool(Object obj,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached(inline = false) GilNode gil) {
            gil.release(true);
            try {
                return lib.asBoolean(obj);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } finally {
                gil.acquire();
            }
        }

        @Specialization(guards = "lib.fitsInLong(obj)")
        Object doLong(Object obj,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached(inline = false) GilNode gil) {
            assert !lib.isBoolean(obj);
            gil.release(true);
            try {
                return lib.asLong(obj);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } finally {
                gil.acquire();
            }
        }

        @Specialization(guards = {"!lib.fitsInLong(obj)", "lib.fitsInBigInteger(obj)"})
        Object doBigInt(Object obj,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached(inline = false) GilNode gil,
                        @Cached(inline = false) PythonObjectFactory factory) {
            assert !lib.isBoolean(obj);
            gil.release(true);
            try {
                return factory.createInt(lib.asBigInteger(obj));
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } finally {
                gil.acquire();
            }
        }

        @Specialization(guards = {"!lib.fitsInLong(obj)", "!lib.fitsInBigInteger(obj)", "lib.fitsInDouble(obj)"})
        Object doDouble(Object obj,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached(inline = false) GilNode gil) {
            assert !lib.isBoolean(obj);
            gil.release(true);
            try {
                return lib.asDouble(obj);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            } finally {
                gil.acquire();
            }
        }

        @Fallback
        @SuppressWarnings("unused")
        public static Object doGeneric(Object left) {
            return null;
        }
    }

    // TODO: remove once all dunder methods are converted to slots and to
    // NormalizeForeignForBinopNode
    abstract static class ForeignBinaryNode extends BinaryOpBuiltinNode {
        @Child private BinaryOpNode op;
        protected final boolean reverse;

        protected ForeignBinaryNode(BinaryOpNode op, boolean reverse) {
            this.op = op;
            this.reverse = reverse;
        }

        @Specialization(guards = "lib.isBoolean(left)")
        Object doComparisonBool(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil) {
            boolean leftBoolean;
            gil.release(true);
            try {
                leftBoolean = lib.asBoolean(left);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to boolean as it claims to");
            } finally {
                gil.acquire();
            }
            if (!reverse) {
                return op.executeObject(frame, leftBoolean, right);
            } else {
                return op.executeObject(frame, right, leftBoolean);
            }

        }

        @Specialization(guards = "lib.fitsInLong(left)")
        Object doComparisonLong(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil) {
            assert !lib.isBoolean(left);
            long leftLong;
            gil.release(true);
            try {
                leftLong = lib.asLong(left);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to long as it claims to");
            } finally {
                gil.acquire();
            }
            if (!reverse) {
                return op.executeObject(frame, leftLong, right);
            } else {
                return op.executeObject(frame, right, leftLong);
            }
        }

        @Specialization(guards = {"!lib.fitsInLong(left)", "lib.fitsInBigInteger(left)"})
        Object doComparisonBigInt(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil,
                        @Cached.Exclusive @Cached PythonObjectFactory factory) {
            assert !lib.isBoolean(left);
            BigInteger leftBigInteger;
            PInt leftInt;
            gil.release(true);
            try {
                leftBigInteger = lib.asBigInteger(left);
                leftInt = factory.createInt(leftBigInteger);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to BigInteger as it claims to");
            } finally {
                gil.acquire();
            }
            if (!reverse) {
                return op.executeObject(frame, leftInt, right);
            } else {
                return op.executeObject(frame, right, leftInt);
            }
        }

        @Specialization(guards = {"!lib.fitsInLong(left)", "!lib.fitsInBigInteger(left)", "lib.fitsInDouble(left)"})
        Object doComparisonDouble(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil) {
            assert !lib.isBoolean(left);
            double leftDouble;
            gil.release(true);
            try {
                leftDouble = lib.asDouble(left);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to double as it claims to");
            } finally {
                gil.acquire();
            }
            if (!reverse) {
                return op.executeObject(frame, leftDouble, right);
            } else {
                return op.executeObject(frame, right, leftDouble);
            }
        }

        @SuppressWarnings("unused")
        @Fallback
        public static PNotImplemented doGeneric(Object left, Object right) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }
    }

    @GenerateInline
    @GenerateCached(false)
    abstract static class ForeignBinarySlotNode extends Node {
        abstract Object execute(VirtualFrame frame, Node inliningTarget, Object left, Object right, BinaryOpNode binaryOpNode);

        @Specialization
        static Object doIt(VirtualFrame frame, Node inliningTarget, Object left, Object right, BinaryOpNode op,
                        @Cached IsForeignObjectNode isForeignLeft,
                        @Cached IsForeignObjectNode isForeignRight,
                        @Cached NormalizeForeignForBinopNode normalizeLeft,
                        @Cached NormalizeForeignForBinopNode normalizeRight) {
            boolean leftIsForeign = isForeignLeft.execute(inliningTarget, left);
            boolean rightIsForeign = isForeignRight.execute(inliningTarget, right);
            if (!leftIsForeign && !rightIsForeign) {
                return PNotImplemented.NOT_IMPLEMENTED;
            }

            Object newLeft = normalizeLeft.execute(inliningTarget, left);
            Object newRight = normalizeRight.execute(inliningTarget, right);
            assert newLeft == null || !IsForeignObjectNode.executeUncached(newLeft) : newLeft;
            assert newRight == null || !IsForeignObjectNode.executeUncached(newRight) : newRight;
            if (newLeft == null || newRight == null) {
                return PNotImplemented.NOT_IMPLEMENTED;
            }
            return op.executeObject(frame, newLeft, newRight);
        }
    }

    @Slot(value = SlotKind.nb_add, isComplex = true)
    @GenerateNodeFactory
    abstract static class AddNode extends BinaryOpBuiltinNode {
        @Specialization
        static Object doIt(VirtualFrame frame, Object left, Object right,
                        @Bind("this") Node inliningTarget,
                        @Cached ForeignBinarySlotNode binarySlotNode,
                        @Cached(inline = false) PyNumberAddNode addNode) {
            return binarySlotNode.execute(frame, inliningTarget, left, right, addNode);
        }
    }

    @Slot(value = SlotKind.nb_multiply, isComplex = true)
    @GenerateNodeFactory
    abstract static class MulNode extends BinaryOpBuiltinNode {
        @Specialization
        static Object doIt(VirtualFrame frame, Object left, Object right,
                        @Bind("this") Node inliningTarget,
                        @Cached ForeignBinarySlotNode binarySlotNode,
                        @Cached(inline = false) PyNumberMultiplyNode mulNode) {
            return binarySlotNode.execute(frame, inliningTarget, left, right, mulNode);
        }
    }

    @Builtin(name = J___SUB__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class SubNode extends ForeignBinaryNode {
        SubNode() {
            super(BinaryArithmetic.Sub.create(), false);
        }
    }

    @Builtin(name = J___RSUB__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class RSubNode extends ForeignBinaryNode {
        RSubNode() {
            super(BinaryArithmetic.Sub.create(), true);
        }
    }

    @Builtin(name = J___TRUEDIV__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class TrueDivNode extends ForeignBinaryNode {
        TrueDivNode() {
            super(BinaryArithmetic.TrueDiv.create(), false);
        }
    }

    @Builtin(name = J___RTRUEDIV__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class RTrueDivNode extends ForeignBinaryNode {
        RTrueDivNode() {
            super(BinaryArithmetic.TrueDiv.create(), true);
        }
    }

    @Builtin(name = J___FLOORDIV__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class FloorDivNode extends ForeignBinaryNode {
        FloorDivNode() {
            super(BinaryArithmetic.FloorDiv.create(), false);
        }
    }

    @Builtin(name = J___RFLOORDIV__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class RFloorDivNode extends ForeignBinaryNode {
        RFloorDivNode() {
            super(BinaryArithmetic.FloorDiv.create(), true);
        }
    }

    @Builtin(name = J___DIVMOD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class DivModNode extends ForeignBinaryNode {
        DivModNode() {
            super(BinaryArithmetic.DivMod.create(), false);
        }
    }

    @Builtin(name = J___RDIVMOD__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class RDivModNode extends ForeignBinaryNode {
        RDivModNode() {
            super(BinaryArithmetic.DivMod.create(), true);
        }
    }

    public abstract static class ForeignBinaryComparisonNode extends PythonBinaryBuiltinNode {
        @Child private LookupAndCallBinaryNode comparisonNode;

        protected ForeignBinaryComparisonNode(SpecialMethodSlot slot, SpecialMethodSlot rslot) {
            comparisonNode = LookupAndCallBinaryNode.create(slot, rslot, true, true);
        }

        @Specialization(guards = {"lib.isBoolean(left)"})
        Object doComparisonBool(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil) {
            boolean leftBoolean;
            gil.release(true);
            try {
                leftBoolean = lib.asBoolean(left);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to boolean for comparison as it claims to");
            } finally {
                gil.acquire();
            }
            return comparisonNode.executeObject(frame, leftBoolean, right);
        }

        @Specialization(guards = {"lib.fitsInLong(left)"})
        Object doComparisonLong(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil) {
            long leftLong;
            gil.release(true);
            try {
                leftLong = lib.asLong(left);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to long for comparison as it claims to");
            } finally {
                gil.acquire();
            }
            return comparisonNode.executeObject(frame, leftLong, right);
        }

        @Specialization(guards = {"!lib.fitsInLong(left)", "lib.fitsInBigInteger(left)"})
        Object doComparisonBigInt(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil,
                        @Cached PythonObjectFactory factory) {
            BigInteger leftBigInteger;
            PInt leftInt;
            gil.release(true);
            try {
                leftBigInteger = lib.asBigInteger(left);
                leftInt = factory.createInt(leftBigInteger);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to BigInteger as it claims to");
            } finally {
                gil.acquire();
            }
            return comparisonNode.executeObject(frame, leftInt, right);
        }

        @Specialization(guards = {"lib.fitsInDouble(left)"})
        Object doComparisonDouble(VirtualFrame frame, Object left, Object right,
                        @Shared @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Shared @Cached GilNode gil) {
            double leftDouble;
            gil.release(true);
            try {
                leftDouble = lib.asDouble(left);
            } catch (UnsupportedMessageException e) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                throw new IllegalStateException("object does not unpack to double for comparison as it claims to");
            } finally {
                gil.acquire();
            }
            return comparisonNode.executeObject(frame, leftDouble, right);
        }

        @Specialization(guards = "lib.isNull(left)")
        Object doComparison(VirtualFrame frame, @SuppressWarnings("unused") Object left, Object right,
                        @SuppressWarnings("unused") @Shared @CachedLibrary(limit = "3") InteropLibrary lib) {
            return comparisonNode.executeObject(frame, PNone.NONE, right);
        }

        @SuppressWarnings("unused")
        @Fallback
        public static PNotImplemented doGeneric(Object left, Object right) {
            return PNotImplemented.NOT_IMPLEMENTED;
        }
    }

    @Builtin(name = J___LT__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LtNode extends ForeignBinaryComparisonNode {
        protected LtNode() {
            super(SpecialMethodSlot.Lt, SpecialMethodSlot.Gt);
        }
    }

    @Builtin(name = J___LE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class LeNode extends ForeignBinaryComparisonNode {
        protected LeNode() {
            super(SpecialMethodSlot.Le, SpecialMethodSlot.Ge);
        }
    }

    @Builtin(name = J___GT__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GtNode extends ForeignBinaryComparisonNode {
        protected GtNode() {
            super(SpecialMethodSlot.Gt, SpecialMethodSlot.Lt);
        }
    }

    @Builtin(name = J___GE__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class GeNode extends ForeignBinaryComparisonNode {
        protected GeNode() {
            super(SpecialMethodSlot.Ge, SpecialMethodSlot.Le);
        }
    }

    @Builtin(name = J___EQ__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    public abstract static class EqNode extends ForeignBinaryComparisonNode {
        protected EqNode() {
            super(SpecialMethodSlot.Eq, SpecialMethodSlot.Eq);
        }
    }

    @Builtin(name = J___INDEX__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class IndexNode extends PythonUnaryBuiltinNode {
        @Specialization(limit = "3")
        protected static Object doIt(Object object,
                        @Cached PRaiseNode raiseNode,
                        @CachedLibrary("object") InteropLibrary lib,
                        @Cached GilNode gil,
                        @Cached PythonObjectFactory factory) {
            gil.release(true);
            try {
                if (lib.isBoolean(object)) {
                    try {
                        return PInt.intValue(lib.asBoolean(object));
                    } catch (UnsupportedMessageException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new IllegalStateException("foreign value claims to be a boolean but isn't");
                    }
                }
                if (lib.fitsInInt(object)) {
                    try {
                        return lib.asInt(object);
                    } catch (UnsupportedMessageException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new IllegalStateException("foreign value claims it fits into index-sized int, but doesn't");
                    }
                }
                if (lib.fitsInLong(object)) {
                    try {
                        return lib.asLong(object);
                    } catch (UnsupportedMessageException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new IllegalStateException("foreign value claims it fits into index-sized long, but doesn't");
                    }
                }
                if (lib.fitsInBigInteger(object)) {
                    try {
                        var big = lib.asBigInteger(object);
                        return factory.createInt(big);
                    } catch (UnsupportedMessageException e) {
                        CompilerDirectives.transferToInterpreterAndInvalidate();
                        throw new IllegalStateException("foreign value claims to be a big integer but isn't");
                    }
                }
                throw raiseNode.raiseIntegerInterpretationError(object);
            } finally {
                gil.acquire();
            }
        }
    }

    @Builtin(name = J___STR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class StrNode extends PythonUnaryBuiltinNode {
        @Child private TruffleString.SwitchEncodingNode switchEncodingNode;

        @Specialization
        Object str(VirtualFrame frame, Object object,
                        @Bind("this") Node inliningTarget,
                        @CachedLibrary(limit = "3") InteropLibrary lib,
                        @Cached GilNode gil,
                        @Cached PyObjectStrAsTruffleStringNode strNode,
                        @Cached InlinedBranchProfile isBoolean,
                        @Cached InlinedBranchProfile isLong,
                        @Cached InlinedBranchProfile isDouble,
                        @Cached InlinedBranchProfile isBigInteger,
                        @Cached InlinedBranchProfile defaultCase,
                        @Cached PythonObjectFactory factory) {
            final Object value;
            try {
                if (lib.isBoolean(object)) {
                    isBoolean.enter(inliningTarget);
                    gil.release(true);
                    try {
                        value = lib.asBoolean(object);
                    } finally {
                        gil.acquire();
                    }
                } else if (lib.fitsInLong(object)) {
                    isLong.enter(inliningTarget);
                    gil.release(true);
                    try {
                        value = lib.asLong(object);
                    } finally {
                        gil.acquire();
                    }
                } else if (lib.fitsInDouble(object)) {
                    isDouble.enter(inliningTarget);
                    gil.release(true);
                    try {
                        value = lib.asDouble(object);
                    } finally {
                        gil.acquire();
                    }
                } else if (lib.fitsInBigInteger(object)) {
                    isBigInteger.enter(inliningTarget);
                    gil.release(true);
                    try {
                        value = factory.createInt(lib.asBigInteger(object));
                    } finally {
                        gil.acquire();
                    }
                } else {
                    defaultCase.enter(inliningTarget);
                    return defaultConversion(frame, lib, object);
                }
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere(e);
            }

            return strNode.execute(frame, inliningTarget, value);
        }

        protected TruffleString.SwitchEncodingNode getSwitchEncodingNode() {
            if (switchEncodingNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                switchEncodingNode = insert(TruffleString.SwitchEncodingNode.create());
            }
            return switchEncodingNode;
        }

        protected TruffleString defaultConversion(VirtualFrame frame, InteropLibrary lib, Object object) {
            try {
                return getSwitchEncodingNode().execute(lib.asTruffleString(lib.toDisplayString(object)), TS_ENCODING);
            } catch (UnsupportedMessageException e) {
                throw CompilerDirectives.shouldNotReachHere("toDisplayString result not convertible to String");
            }
        }
    }

    @Builtin(name = J___REPR__, minNumOfPositionalArgs = 1)
    @GenerateNodeFactory
    abstract static class ReprNode extends StrNode {
        @Child private ObjectNodes.DefaultObjectReprNode defaultReprNode;

        @Override
        protected TruffleString defaultConversion(VirtualFrame frame, InteropLibrary lib, Object object) {
            try {
                if (getContext().getEnv().isHostObject(object)) {
                    boolean isMetaObject = lib.isMetaObject(object);
                    Object metaObject = null;
                    if (isMetaObject) {
                        metaObject = object;
                    } else if (lib.hasMetaObject(object)) {
                        metaObject = lib.getMetaObject(object);
                    }
                    if (metaObject != null) {
                        TruffleString displayName = getSwitchEncodingNode().execute(lib.asTruffleString(lib.toDisplayString(metaObject)), TS_ENCODING);
                        return simpleTruffleStringFormatUncached("<%s[%s] at 0x%s>", isMetaObject ? "JavaClass" : "JavaObject", displayName,
                                        PythonAbstractObject.systemHashCodeAsHexString(object));
                    }
                }
            } catch (UnsupportedMessageException e) {
                // fallthrough to default
            }
            if (defaultReprNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                defaultReprNode = insert(ObjectNodes.DefaultObjectReprNode.create());
            }
            return defaultReprNode.executeCached(frame, object);
        }
    }

    @Builtin(name = J___RAND__, minNumOfPositionalArgs = 2)
    @Builtin(name = J___AND__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class AndNode extends PythonBinaryBuiltinNode {
        @Specialization(limit = "3")
        protected static Object op(VirtualFrame frame, Object left, Object right,
                        @Cached BitAndNode andNode,
                        @CachedLibrary("left") InteropLibrary lib,
                        @Cached GilNode gil) {
            if (lib.isNumber(left) && lib.fitsInLong(left)) {
                try {
                    long leftLong;
                    gil.release(true);
                    try {
                        leftLong = lib.asLong(left);
                    } finally {
                        gil.acquire();
                    }
                    return andNode.executeObject(frame, leftLong, right);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            } else {
                return PNotImplemented.NOT_IMPLEMENTED;
            }
        }
    }

    @Builtin(name = J___ROR__, minNumOfPositionalArgs = 2)
    @Builtin(name = J___OR__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class OrNode extends PythonBinaryBuiltinNode {
        @Specialization(limit = "3")
        protected static Object op(VirtualFrame frame, Object left, Object right,
                        @Cached BitOrNode orNode,
                        @CachedLibrary("left") InteropLibrary lib,
                        @Cached GilNode gil) {
            if (lib.isNumber(left) && lib.fitsInLong(left)) {
                try {
                    long leftLong;
                    gil.release(true);
                    try {
                        leftLong = lib.asLong(left);
                    } finally {
                        gil.acquire();
                    }
                    return orNode.executeObject(frame, leftLong, right);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            } else {
                return PNotImplemented.NOT_IMPLEMENTED;
            }
        }
    }

    @Builtin(name = J___RXOR__, minNumOfPositionalArgs = 2)
    @Builtin(name = J___XOR__, minNumOfPositionalArgs = 2)
    @GenerateNodeFactory
    abstract static class XorNode extends PythonBinaryBuiltinNode {
        @Specialization(limit = "3")
        protected static Object op(VirtualFrame frame, Object left, Object right,
                        @Cached BitXorNode xorNode,
                        @CachedLibrary("left") InteropLibrary lib,
                        @Cached GilNode gil) {
            if (lib.isNumber(left) && lib.fitsInLong(left)) {
                try {
                    long leftLong;
                    gil.release(true);
                    try {
                        leftLong = lib.asLong(left);
                    } finally {
                        gil.acquire();
                    }
                    return xorNode.executeObject(frame, leftLong, right);
                } catch (UnsupportedMessageException e) {
                    throw CompilerDirectives.shouldNotReachHere();
                }
            } else {
                return PNotImplemented.NOT_IMPLEMENTED;
            }
        }
    }
}
