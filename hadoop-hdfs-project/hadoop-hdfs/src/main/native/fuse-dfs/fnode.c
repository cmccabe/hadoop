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

#include "interval_tree.h"
#include "fnode.h"
#include "tree.h"

#include <stdint.h>

#define FDBUF_LEN (1024 * 1024)
#define FDFLAGS_LEN (FDATA_LEN / 4) 
#define FDFLAG_DIRTY 0x1
#define FDFLAG_VALID 0x2

static int fdata_compare(const struct fdata *a, const struct fdata *b)
  __attribute__((const));

struct fdata {
  RB_ENTRY(fdata) entry;
  /** data */
  uint8_t fdbuf[FDATA_LEN];
  /** flags */
  uint8_t fdflags[FLAGS_LEN];
};

RB_HEAD(fdata_tree, fdata);

struct fnode {
  RB_ENTRY(fnode) entry;
  /** reference count */
  int64_t refcnt;
  /** full path, minus the trailing slash */
  const char *fullpath;
  /** cached data nodes */
  struct fdata_tree *ftree;
  /** nonzero if there are dirty fdata nodes. */
  int dirty;
  /** length at which this file has been truncated */
  int64_t trunc_len;
  /** lock which protects this fnode */
  pthread_mutex_t lock;
  /** hdfs file for write, or NULL */
  hdfsFile writeFile;
  /** hdfs file for read, or NULL */
  hdfsFile readFile;
};

RB_HEAD(fnode_tree, fnode);

static pthread_mutex_t g_fnode_lock;

static fnode_tree g_fnodes; 

static int fdata_compare(const struct fdata *a, const struct fdata *b)
{
  if (a->offset < b->offset)
    return -1;
  else if (a->offset > b->offset)
    return 1;
  return 0;
}

int fnode_init(const char *fdir, int64_t flimit)
{
  int mutex_init = 0, ret;
  ret = pthread_mutex_init(&g_fnode_lock, NULL);
  if (ret) {
    goto eror;
  }
  mutex_init = 1;
  RB_INIT(&g_fnodes);
  return 0;

error:
  if (mutex_init) {
    pthread_mutex_destroy(&g_fnode_lock);
  }
  return ret < 0 ? -ret : ret;
}

static void fdata_free(struct fdata *ad)
{
  free(ad);
}

static void fdata_invalidate_after(struct fdata *ad, int64_t len)
{
  int32_t foff;

  if (ad->offset + FDATA_LEN < len) {
    return;
  }
  ... set bits as appropriate ...
  ... memzero the rest ...
}

void fnode_truncate(struct fnode *fn, int64_t len)
{
  struct fdata *ad, *bd;

  if (fn->trunc_len < len) {
    return;
  }
  RB_FOREACH_REVERSE_SAFE(ad, fdata_tree, &fn->ftree, bd) {
    if (ad->offset < len) {
      fdata_invalidate_after(ad, len);
      break;
    }
    fdata_free(ad);
  }
  fn->dirty = 1;
}

static void fnode_free(struct fnode *fn)
{
  struct fdata *ad, *bd;

  RB_FOREACH_SAFE(ad, fdata_tree, &fn->ftree, bd) {
    fdata_free(ad);
  }
  pthread_mutex_destroy(&fn->lock);
  free(fn->fullpath);
  free(fn);
}

static int fnode_open_internal(struct fnode *fn, int write)
{
  ...  hdfsOpen ...
}

static int fnode_create(struct fnode **fn, const char *fullpath, int flags)
{
  int ret;
  struct fnode *an;

  an = xcalloc(1, sizeof(*an));
  an->fullpath = xstrcpy(fullpath);
  an->refcnt = 1;
  if ((flags & O_ACCMODE) == O_RDONLY) {
    ret = fnode_open_internal(fn, 0);
  } else if (!(flags & O_CREAT)) {
    if ((flags & O_ACCMODE) == O_RDWR) {
      ret = fnode_open_internal(fn, 0);
    } else {
      ret = fnode_open_internal(fn, 1);
    }
  }
  if (ret) {
    fnode_free(an);
    return ret;
  }
  *fn = an;
  return 0;
}

int fnode_ref(struct fnode **fn, const char *fullpath, int flags)
{
  struct fnode exemplar, *an;
  int must_exist = 0;

  memset(&exemplar, 0, sizeof(exemplar));
  exemplar.fullpath = fullpath;
  pthread_mutex_lock(&g_fnode_lock);
  an = RB_FIND(fnode_tree, &g_fnodes);
  if (!an) {
    if ((flags & O_ACCMODE) == O_RDONLY) {
      /* If we're opening a file for O_RDONLY, it must exist. */
      must_exist = 1;
    } else if (!(flags & O_CREAT)) {
      /* If we didn't give O_CREAT, the file must exist. */
      must_exist = 1;
    }
    ret = fnode_create(fn, fullpath, flags);
    pthread_mutex_unlock(&g_fnode_lock);
    return ret;
  }
  *fn = an;
  if (flags & O_TRUNC) {
    pthread_mutex_lock(&an->lock);
    pthread_mutex_unlock(&g_fnode_lock);
    fnode_truncate(an, 0);
    pthread_mutex_unlock(&an->lock);
  } else {
    pthread_mutex_unlock(&g_fnode_lock);
  }
  return 0;
}

int32_t fnode_read(struct fnode *fn, int64_t pos, int32_t amt, uint8_t *out)
{
}

int fnode_write(struct fnode *fn, int64_t pos, int32_t amt,
                const uint8_t *in)
{
}

int fnode_begin_flush(struct fnode *fn)
{
}

int fnode_join_flush(struct fnode *fn)
{
}

void fnode_unref(struct fnode *fn)
{
  pthread_mutex_lock(g_fnode_lock);
  pthread_mutex_lock(fn->lock);
  fn->refcnt--;
  if (fn->refcnt > 0) {
    pthread_mutex_unlock(fn->lock);
    pthread_mutex_unlock(g_fnode_lock);
    return;
  }
  if (fn->dirty) {
    fprintf(stderr, "ERROR: unref'ed dirty fnode %p\n", fn);
  }
  RB_REMOVE(fnode_tree, &g_fnodes, fn);
  pthread_mutex_unlock(fn->lock);
  fnode_free(fn);
  pthread_mutex_unlock(g_fnode_lock);
}
