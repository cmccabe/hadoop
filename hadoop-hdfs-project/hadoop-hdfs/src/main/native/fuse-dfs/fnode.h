/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#ifndef FUSE_DFS_LOADER_H
#define FUSE_DFS_LOADER_H

#include <stdint.h> /* for int64_t, etc. */

/**
 * The fnode subsystem handles Flush Nodes (fnodes).
 *
 * Flush Nodes cache data from a file, allowing users to do random writes as
 * POSIX specifies.  The fnode subsystem does as much as possible in the
 * background with thread pools, so that operations don't block unless they
 * must.
 */

struct fnode;

/**
 * Initialize the fnode subsystem.  This function must be called before any
 * other fnode function.
 *
 * @param fdir     The fnode directory to use.
 * @param flimit   The maximum number of bytes to cache.
 *
 * @return         0 on success; negative error code otherwise.
 */
int fnode_init(const char *fdir, int64_t flimit);

/**
 * Create an fnode.
 *
 * @param fn       (out param) the fnode.
 * @param path     The HDFS path to open.
 * @param flags    The POSIX flags to use.
 *                 You must specify O_RDONLY, O_WRONLY, or O_RDWR.
 *                 Additionally, O_TRUNC and O_CREAT are accepted.
 *
 * @return         0 on success; negative error code otherwise
 */
int fnode_create(struct fnode **fn, const char *path, int flags);

/**
 * Fetch some bytes from an fnode.
 * This function is safe to call from multiple threads at once.
 *
 * @param fn       The fnode.
 * @param pos      The position to start reading at.
 * @param amt      The number of bytes to read.
 * @param out      (out param) buffer to fill.
 *
 * @return         If positive, the number of bytes which were fetched.
 *                 Short reads are possible.
 *                 If zero, there is nothing at the given offset (in other
 *                 words, we got EOF).
 *                 If negative, the error code.
 */
int32_t fnode_read(struct fnode *fn, int64_t pos, int32_t amt, uint8_t *out);

/**
 * Write some bytes to an fnode.
 * This function is safe to call from multiple threads at once.
 *
 * @param fn       The fnode.
 * @param pos      The position to start writing at.
 * @param amt      The number of bytes to write.
 * @param in       buffer to write.
 *
 * @return         0 on success; negative error code otherwise.
 *                 Partial writes are not possible.
 */
int fnode_write(struct fnode *fn, int64_t pos, int32_t amt,
                const uint8_t *in);

/**
 * Start flushing an fnode back to HDFS.
 *
 * This function is safe to call from multiple threads at once.
 * You can still call fnode_read or fnode_write on the fnode after this.  Doing
 * more writes may delay the flush progress.
 *
 * @param fn       The fnode.
 *
 * @return         0 on success; negative error code otherwise.
 *                 Note that just because you started the flush, doesn't mean
 *                 the data is in HDFS yet!  It just means the process has
 *                 started.
 */
int fnode_begin_flush(struct fnode *fn);

/**
 * Block until there is no flush in progress.
 *
 * This function is safe to call from multiple threads at once.
 *
 * @param fn       The fnode.
 *
 * @return         0 on success; negative error code otherwise.
 */
int fnode_join_flush(struct fnode *fn);

/**
 * Dispose of an fnode.
 *
 * This function NOT thread-safe.
 *
 * The fnode must not be dirty when it is disposed.  In other words, there must
 * be no unflushed writes.
 *
 * @param fn       The fnode to dispose of.
 *
 * @return         0 on success; negative error code otherwise.
 */
int fnode_dispose(struct fnode *fn);

#endif
