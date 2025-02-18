/* MIT License
 *
 * Copyright (c) 2023, 2024, Oracle and/or its affiliates.
 * Copyright (c) 2019 pyhandle
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

#include "trace_internal.h"
#include "autogen_trace_ctx_init.h"

// NOTE: at the moment this function assumes that uctx is always the
// same. If/when we migrate to a system in which we can have multiple
// independent contexts, this function should ensure to create a different
// debug wrapper for each of them.
int hpy_trace_ctx_init(HPyContext *tctx, HPyContext *uctx)
{
    if (tctx->_private != NULL) {
        // already initialized
        assert(get_info(tctx)->uctx == uctx); // sanity check
        return 0;
    }
    // initialize trace_info
    // XXX: currently we never free this malloc
    HPyTraceInfo *info = malloc(sizeof(HPyTraceInfo));
    if (info == NULL) {
        HPyErr_NoMemory(uctx);
        return -1;
    }
#ifdef _WIN32
    (void)QueryPerformanceFrequency(&info->counter_freq);
#else
    clock_getres(CLOCK_MONOTONIC_RAW, &info->counter_freq);
#endif
    trace_ctx_init_info(info, uctx);
    tctx->_private = info;
    trace_ctx_init_fields(tctx, uctx);
    return 0;
}

int hpy_trace_ctx_free(HPyContext *tctx)
{
    trace_ctx_free_info(get_info(tctx));
    return 0;
}

static HPy create_trace_func_args(HPyContext *uctx, int id)
{
    HPy h_name = HPyUnicode_FromString(uctx, hpy_trace_get_func_name(id));
    if (HPy_IsNull(h_name))
        goto fail;
    HPy h_args = HPyTuple_FromArray(uctx, &h_name, 1);
    if (HPy_IsNull(h_args))
        goto fail;
    HPy_Close(uctx, h_name);
    return h_args;
fail:
    HPy_FatalError(uctx, "could not create arguments for user trace function");
    return HPy_NULL;
}

static inline void
update_duration(_HPyTime_t *res, _HPyTime_t *start, _HPyTime_t *end)
{
#ifdef _WIN32
    res->QuadPart += end->QuadPart - start->QuadPart;
    assert(res->QuadPart >= 0);
#else
    /* Normalize: since the clock is guaranteed to be monotonic, we know that
       'end >= start'. It can still happen that 'end->tv_nsec < start->tv_nsec'
       but in this case, we know that 'end->tv_sec > start->tv_sec'. */
    if (end->tv_nsec < start->tv_nsec) {
        assert(end->tv_sec > start->tv_sec);
        res->tv_sec += end->tv_sec - start->tv_sec - 1,
        res->tv_nsec += end->tv_nsec - start->tv_nsec + FREQ_NSEC;
    } else {
        res->tv_sec += end->tv_sec - start->tv_sec,
        res->tv_nsec += end->tv_nsec - start->tv_nsec;
    }
    assert(res->tv_sec >= 0);
    assert(res->tv_nsec >= 0);
#endif
}

HPyTraceInfo *hpy_trace_on_enter(HPyContext *tctx, int id)
{
    HPyTraceInfo *tctx_info = get_info(tctx);
    HPyContext *uctx = tctx_info->uctx;
    HPy args, res;
    tctx_info->call_counts[id]++;
    if(!HPy_IsNull(tctx_info->on_enter_func)) {
        args = create_trace_func_args(uctx, id);
        res = HPy_CallTupleDict(
                uctx, tctx_info->on_enter_func, args, HPy_NULL);
        HPy_Close(uctx, args);
        if (HPy_IsNull(res)) {
            HPy_FatalError(uctx,
                    "error when executing on-enter trace function");
        }
    }
    return tctx_info;
}

#ifdef _WIN32
#define CLOCK_FAILED(_R0, _R1) (!(_R0) || !(_R1))
#else
#define CLOCK_FAILED(_R0, _R1) ((_R0) + (_R1))
#endif

void hpy_trace_on_exit(HPyTraceInfo *info, int id, _HPyClockStatus_t r0,
        _HPyClockStatus_t r1, _HPyTime_t *_ts_start, _HPyTime_t *_ts_end)
{
    HPyContext *uctx = info->uctx;
    HPy args, res;
    if (CLOCK_FAILED(r0, r1))
    {
        printf("Could not get monotonic clock in %s\n", hpy_trace_get_func_name(id));
        fflush(stdout);
        HPy_FatalError(uctx, "could not get monotonic clock123");
    }
    update_duration(&info->durations[id], _ts_start, _ts_end);
    if(!HPy_IsNull(info->on_exit_func)) {
        args = create_trace_func_args(uctx, id);
        res = HPy_CallTupleDict(uctx, info->on_exit_func, args, HPy_NULL);
        HPy_Close(uctx, args);
        if (HPy_IsNull(res)) {
            HPy_FatalError(uctx,
                    "error when executing on-exit trace function");
        }
    }
}

