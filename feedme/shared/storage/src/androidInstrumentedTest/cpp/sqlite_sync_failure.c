/* TEST APK ONLY: registered, URI-selected forwarding VFS for the actual bundled SQLite.
 * Its unix VFS calls fsync directly, not through xSetSystemCall. Injected xSync returns
 * SQLITE_IOERR_FSYNC before delegation. Injected xDelete delegates exact journal unlink
 * with syncDir=0, then returns SQLITE_IOERR_DIR_FSYNC. Neither is an actual OS errno.
 * Public ABI: https://www.sqlite.org/c3ref/vfs.html and io_methods.html .
 * No registered VFS/method table, global default, GOT or production driver is modified.
 */
#include <jni.h>
#include <dlfcn.h>
#include <limits.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef int64_t sqlite3_int64;
typedef struct sqlite3_vfs sqlite3_vfs;
typedef struct sqlite3_file sqlite3_file;
typedef struct sqlite3_io_methods sqlite3_io_methods;
typedef void (*sqlite3_syscall_ptr)(void);
struct sqlite3_file { const sqlite3_io_methods *pMethods; };
struct sqlite3_io_methods {
    int iVersion;
    int (*xClose)(sqlite3_file *);
    int (*xRead)(sqlite3_file *, void *, int, sqlite3_int64);
    int (*xWrite)(sqlite3_file *, const void *, int, sqlite3_int64);
    int (*xTruncate)(sqlite3_file *, sqlite3_int64);
    int (*xSync)(sqlite3_file *, int);
    int (*xFileSize)(sqlite3_file *, sqlite3_int64 *);
    int (*xLock)(sqlite3_file *, int);
    int (*xUnlock)(sqlite3_file *, int);
    int (*xCheckReservedLock)(sqlite3_file *, int *);
    int (*xFileControl)(sqlite3_file *, int, void *);
    int (*xSectorSize)(sqlite3_file *);
    int (*xDeviceCharacteristics)(sqlite3_file *);
    int (*xShmMap)(sqlite3_file *, int, int, int, void volatile **);
    int (*xShmLock)(sqlite3_file *, int, int, int);
    void (*xShmBarrier)(sqlite3_file *);
    int (*xShmUnmap)(sqlite3_file *, int);
    int (*xFetch)(sqlite3_file *, sqlite3_int64, int, void **);
    int (*xUnfetch)(sqlite3_file *, sqlite3_int64, void *);
};
struct sqlite3_vfs {
    int iVersion, szOsFile, mxPathname;
    sqlite3_vfs *pNext;
    const char *zName;
    void *pAppData;
    int (*xOpen)(sqlite3_vfs *, const char *, sqlite3_file *, int, int *);
    int (*xDelete)(sqlite3_vfs *, const char *, int);
    int (*xAccess)(sqlite3_vfs *, const char *, int, int *);
    int (*xFullPathname)(sqlite3_vfs *, const char *, int, char *);
    void *(*xDlOpen)(sqlite3_vfs *, const char *);
    void (*xDlError)(sqlite3_vfs *, int, char *);
    void (*(*xDlSym)(sqlite3_vfs *, void *, const char *))(void);
    void (*xDlClose)(sqlite3_vfs *, void *);
    int (*xRandomness)(sqlite3_vfs *, int, char *);
    int (*xSleep)(sqlite3_vfs *, int);
    int (*xCurrentTime)(sqlite3_vfs *, double *);
    int (*xGetLastError)(sqlite3_vfs *, int, char *);
    int (*xCurrentTimeInt64)(sqlite3_vfs *, sqlite3_int64 *);
    int (*xSetSystemCall)(sqlite3_vfs *, const char *, sqlite3_syscall_ptr);
    sqlite3_syscall_ptr (*xGetSystemCall)(sqlite3_vfs *, const char *);
    const char *(*xNextSystemCall)(sqlite3_vfs *, const char *);
};

enum { OK = 0, NOMEM = 7, IOERR = 10, CANTOPEN = 14, IOERR_FSYNC = 1034,
    IOERR_DIR_FSYNC = 1290, FCNTL_FILE_POINTER = 7, FCNTL_VFSNAME = 12, FCNTL_VFS_POINTER = 27 };
static const char VFS_NAME[] = "feedme-test-sync-failure-v1";
/* calloc gives the native file its own max-aligned allocation; no guessed header offset. */
typedef struct { sqlite3_file base; sqlite3_file *real; char path[PATH_MAX]; } TestFile;
static pthread_mutex_t gate = PTHREAD_MUTEX_INITIALIZER;
static void *library;
static sqlite3_vfs wrapper, *native;
static sqlite3_vfs *(*find_vfs)(const char *);
static int (*register_vfs)(sqlite3_vfs *, int), (*unregister_vfs)(sqlite3_vfs *);
static char *(*sqlite_mprintf)(const char *, ...);
static int registered, installed, open_files, target, nth, matches, setup_stage;
static char database[PATH_MAX], journal[PATH_MAX];
static struct stat database_identity;
/* journal xSync, database xSync, reserved, journal xDelete(syncDir), failures, code, delegated unlink */
static jlong counters[7];

static void fail(JNIEnv *env) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    char message[128];
    snprintf(message, sizeof(message), "Isolated SQLite test VFS unavailable [stage=%d,files=%d]", setup_stage, open_files);
    if (type) (*env)->ThrowNew(env, type, message);
}
static int owned_kind(const char *path) {
    struct stat a;
    if (!path || lstat(path, &a) || !S_ISREG(a.st_mode) || a.st_nlink != 1 ||
        a.st_uid != getuid() || (a.st_mode & 0077)) return 0;
    if (!strcmp(path, database) && a.st_dev == database_identity.st_dev && a.st_ino == database_identity.st_ino) return 2;
    return !strcmp(path, journal) ? 1 : 0;
}
static int should_inject(const char *path, int operation) {
    pthread_mutex_lock(&gate);
    int kind = installed ? owned_kind(path) : 0;
    if (operation == 4) kind = kind == 1 ? 4 : 0;
    if (kind) counters[kind - 1]++;
    int inject = installed && kind == target && ++matches == nth;
    pthread_mutex_unlock(&gate);
    return inject;
}
static void record_failure(int code, int unlinked) {
    pthread_mutex_lock(&gate);
    counters[4]++; counters[5] = code; counters[6] += unlinked;
    pthread_mutex_unlock(&gate);
}
static sqlite3_file *real(sqlite3_file *f) { return ((TestFile *)f)->real; }
static int test_close(sqlite3_file *f) {
    int rc = real(f)->pMethods->xClose(real(f));
    if (rc == OK) {
        free(real(f)); ((TestFile *)f)->real = NULL; f->pMethods = NULL;
        pthread_mutex_lock(&gate); open_files--; pthread_mutex_unlock(&gate);
    }
    /* A failed underlying close deliberately prevents unregister/library release. */
    return rc;
}
static int test_read(sqlite3_file *f, void *p, int n, sqlite3_int64 o) { return real(f)->pMethods->xRead(real(f), p, n, o); }
static int test_write(sqlite3_file *f, const void *p, int n, sqlite3_int64 o) { return real(f)->pMethods->xWrite(real(f), p, n, o); }
static int test_truncate(sqlite3_file *f, sqlite3_int64 n) { return real(f)->pMethods->xTruncate(real(f), n); }
static int test_sync(sqlite3_file *f, int flags) {
    if (should_inject(((TestFile *)f)->path, 0)) { record_failure(IOERR_FSYNC, 0); return IOERR_FSYNC; }
    return real(f)->pMethods->xSync(real(f), flags);
}
static int test_size(sqlite3_file *f, sqlite3_int64 *n) { return real(f)->pMethods->xFileSize(real(f), n); }
static int test_lock(sqlite3_file *f, int n) { return real(f)->pMethods->xLock(real(f), n); }
static int test_unlock(sqlite3_file *f, int n) { return real(f)->pMethods->xUnlock(real(f), n); }
static int test_reserved(sqlite3_file *f, int *n) { return real(f)->pMethods->xCheckReservedLock(real(f), n); }
static int test_control(sqlite3_file *f, int op, void *arg) {
    if (op == FCNTL_FILE_POINTER) { *(sqlite3_file **)arg = f; return OK; }
    if (op == FCNTL_VFS_POINTER) { *(sqlite3_vfs **)arg = &wrapper; return OK; }
    if (op == FCNTL_VFSNAME) {
        *(char **)arg = sqlite_mprintf("%s/%s", VFS_NAME, native->zName);
        return *(char **)arg ? OK : NOMEM;
    }
    return real(f)->pMethods->xFileControl(real(f), op, arg);
}
static int test_sector(sqlite3_file *f) { return real(f)->pMethods->xSectorSize(real(f)); }
static int test_characteristics(sqlite3_file *f) { return real(f)->pMethods->xDeviceCharacteristics(real(f)); }
static int test_shm_map(sqlite3_file *f, int p, int n, int e, void volatile **r) {
    return real(f)->pMethods->xShmMap ? real(f)->pMethods->xShmMap(real(f), p, n, e, r) : IOERR;
}
static int test_shm_lock(sqlite3_file *f, int o, int n, int flags) {
    return real(f)->pMethods->xShmLock ? real(f)->pMethods->xShmLock(real(f), o, n, flags) : IOERR;
}
static void test_shm_barrier(sqlite3_file *f) { if (real(f)->pMethods->xShmBarrier) real(f)->pMethods->xShmBarrier(real(f)); }
static int test_shm_unmap(sqlite3_file *f, int d) { return real(f)->pMethods->xShmUnmap ? real(f)->pMethods->xShmUnmap(real(f), d) : OK; }
static int test_fetch(sqlite3_file *f, sqlite3_int64 o, int n, void **p) {
    if (!real(f)->pMethods->xFetch) { *p = NULL; return OK; }
    return real(f)->pMethods->xFetch(real(f), o, n, p);
}
static int test_unfetch(sqlite3_file *f, sqlite3_int64 o, void *p) {
    return real(f)->pMethods->xUnfetch ? real(f)->pMethods->xUnfetch(real(f), o, p) : OK;
}
#define V1 test_close,test_read,test_write,test_truncate,test_sync,test_size,test_lock,test_unlock,test_reserved,test_control,test_sector,test_characteristics
#define V2 test_shm_map,test_shm_lock,test_shm_barrier,test_shm_unmap
static const sqlite3_io_methods methods1 = {1,V1,NULL,NULL,NULL,NULL,NULL,NULL};
static const sqlite3_io_methods methods2 = {2,V1,V2,NULL,NULL};
static const sqlite3_io_methods methods3 = {3,V1,V2,test_fetch,test_unfetch};
static int test_open(sqlite3_vfs *v, const char *name, sqlite3_file *f, int flags, int *out_flags) {
    (void)v;
    TestFile *t = (TestFile *)f;
    memset(t, 0, sizeof(*t));
    t->real = calloc(1, (size_t)native->szOsFile);
    if (!t->real) return NOMEM;
    if (name && strlen(name) < sizeof(t->path)) strcpy(t->path, name);
    int rc = native->xOpen(native, name, t->real, flags, out_flags);
    /* Failed xOpen with non-NULL native pMethods must still be closed by SQLite. */
    if (t->real->pMethods) {
        int version = t->real->pMethods->iVersion;
        f->pMethods = version >= 3 ? &methods3 : version == 2 ? &methods2 : &methods1;
        pthread_mutex_lock(&gate); open_files++; pthread_mutex_unlock(&gate);
    } else { free(t->real); t->real = NULL; if (rc == OK) rc = CANTOPEN; }
    return rc;
}
static int test_delete(sqlite3_vfs *v, const char *name, int sync_dir) {
    (void)v;
    if (sync_dir && should_inject(name, 4)) {
        int rc = native->xDelete(native, name, 0);
        if (rc != OK) return rc;
        record_failure(IOERR_DIR_FSYNC, 1);
        return IOERR_DIR_FSYNC;
    }
    return native->xDelete(native, name, sync_dir);
}
static int test_access(sqlite3_vfs *v,const char *n,int f,int *r) { (void)v; return native->xAccess(native,n,f,r); }
static int test_path(sqlite3_vfs *v,const char *n,int s,char *r) { (void)v; return native->xFullPathname(native,n,s,r); }
static void *test_dl_open(sqlite3_vfs *v,const char *n) { (void)v; return native->xDlOpen(native,n); }
static void test_dl_error(sqlite3_vfs *v,int n,char *r) { (void)v; native->xDlError(native,n,r); }
static void (*test_dl_sym(sqlite3_vfs *v,void *h,const char *n))(void) { (void)v; return native->xDlSym(native,h,n); }
static void test_dl_close(sqlite3_vfs *v,void *h) { (void)v; native->xDlClose(native,h); }
static int test_random(sqlite3_vfs *v,int n,char *r) { (void)v; return native->xRandomness(native,n,r); }
static int test_sleep(sqlite3_vfs *v,int n) { (void)v; return native->xSleep(native,n); }
static int test_time(sqlite3_vfs *v,double *r) { (void)v; return native->xCurrentTime(native,r); }
static int test_error(sqlite3_vfs *v,int n,char *r) { (void)v; return native->xGetLastError ? native->xGetLastError(native,n,r) : 0; }
static int test_time64(sqlite3_vfs *v,sqlite3_int64 *r) { (void)v; return native->xCurrentTimeInt64(native,r); }

JNIEXPORT void JNICALL Java_com_feedme_storage_SqliteSyncFailureInjector_registerVfs(JNIEnv *env,jobject self) {
    (void)self; pthread_mutex_lock(&gate); setup_stage = 1;
    if (registered) { pthread_mutex_unlock(&gate); return; }
    library = dlopen("libsqliteJni.so", RTLD_NOW | RTLD_NOLOAD);
    setup_stage = 2;
    find_vfs = library ? (sqlite3_vfs *(*)(const char *))dlsym(library,"sqlite3_vfs_find") : NULL;
    register_vfs = library ? (int (*)(sqlite3_vfs *,int))dlsym(library,"sqlite3_vfs_register") : NULL;
    unregister_vfs = library ? (int (*)(sqlite3_vfs *))dlsym(library,"sqlite3_vfs_unregister") : NULL;
    sqlite_mprintf = library ? (char *(*)(const char *,...))dlsym(library,"sqlite3_mprintf") : NULL;
    native = find_vfs ? find_vfs(NULL) : NULL;
    int valid = native && register_vfs && unregister_vfs && sqlite_mprintf && native->iVersion >= 2 &&
        native->zName && !strcmp(native->zName,"unix") && !find_vfs(VFS_NAME) &&
        native->szOsFile > 0 && native->szOsFile < 65536 && native->xCurrentTimeInt64;
    if (valid) {
        setup_stage = 3;
        /* This distinct object is fully configured BEFORE public non-default registration. */
        memset(&wrapper,0,sizeof(wrapper));
        wrapper.iVersion = 2; wrapper.szOsFile = sizeof(TestFile);
        wrapper.mxPathname = native->mxPathname; wrapper.zName = VFS_NAME;
        wrapper.xOpen=test_open; wrapper.xDelete=test_delete; wrapper.xAccess=test_access;
        wrapper.xFullPathname=test_path; wrapper.xDlOpen=test_dl_open; wrapper.xDlError=test_dl_error;
        wrapper.xDlSym=test_dl_sym; wrapper.xDlClose=test_dl_close; wrapper.xRandomness=test_random;
        wrapper.xSleep=test_sleep; wrapper.xCurrentTime=test_time; wrapper.xGetLastError=test_error;
        wrapper.xCurrentTimeInt64=test_time64;
        valid = register_vfs(&wrapper,0) == OK;
    }
    if (valid) { registered=1; setup_stage=4; }
    else { if (library) dlclose(library); library=NULL; native=NULL; }
    pthread_mutex_unlock(&gate); if (!valid) fail(env);
}
JNIEXPORT void JNICALL Java_com_feedme_storage_SqliteSyncFailureInjector_install(
    JNIEnv *env,jobject self,jstring path,jint requested_target,jint requested_nth) {
    (void)self; pthread_mutex_lock(&gate); setup_stage=5;
    int valid=registered && !installed && open_files>0 && requested_nth>=1 &&
        (requested_target==1 || requested_target==2 || requested_target==4);
    if (!valid) { pthread_mutex_unlock(&gate); fail(env); return; }
    const char *input=(*env)->GetStringUTFChars(env,path,NULL);
    if (!input) { pthread_mutex_unlock(&gate); return; }
    char directory[PATH_MAX]; size_t length=strlen(input);
    valid=length>13 && length+9<sizeof(database); setup_stage=6;
    if (valid) {
        memcpy(database,input,length+1); char *slash=strrchr(database,'/');
        valid=slash && !strcmp(slash+1,"state.sqlite");
        if (valid) {
            size_t n=(size_t)(slash-database); memcpy(directory,database,n); directory[n]='\0';
            const char *name=strrchr(directory,'/'); struct stat a;
            valid=name && !strncmp(name+1,"private-state-instrumented-",27) &&
                !lstat(database,&database_identity) && S_ISREG(database_identity.st_mode) &&
                database_identity.st_nlink==1 && database_identity.st_uid==getuid() &&
                !(database_identity.st_mode&0077) && !lstat(directory,&a) && S_ISDIR(a.st_mode) &&
                a.st_uid==getuid() && !(a.st_mode&0077);
            if (valid) snprintf(journal,sizeof(journal),"%s-journal",database);
        }
    }
    (*env)->ReleaseStringUTFChars(env,path,input);
    if (valid) {
        memset(counters,0,sizeof(counters)); target=requested_target; nth=requested_nth;
        matches=0; installed=1; setup_stage=7;
    }
    pthread_mutex_unlock(&gate); if (!valid) fail(env);
}
JNIEXPORT jlongArray JNICALL Java_com_feedme_storage_SqliteSyncFailureInjector_statistics(JNIEnv *env,jobject self) {
    (void)self; jlong copy[7]; pthread_mutex_lock(&gate); memcpy(copy,counters,sizeof(copy)); pthread_mutex_unlock(&gate);
    jlongArray result=(*env)->NewLongArray(env,7); if (result) (*env)->SetLongArrayRegion(env,result,0,7,copy);
    return result;
}
JNIEXPORT void JNICALL Java_com_feedme_storage_SqliteSyncFailureInjector_restore(JNIEnv *env,jobject self) {
    (void)env; (void)self; pthread_mutex_lock(&gate); installed=0; target=0; pthread_mutex_unlock(&gate);
}
JNIEXPORT void JNICALL Java_com_feedme_storage_SqliteSyncFailureInjector_unregisterVfs(JNIEnv *env,jobject self) {
    (void)self; pthread_mutex_lock(&gate); setup_stage=8;
    int valid=!installed && open_files==0;
    if (valid && registered) {
        valid=find_vfs(VFS_NAME)==&wrapper && unregister_vfs(&wrapper)==OK;
        if (valid) { registered=0; dlclose(library); library=NULL; native=NULL; }
    }
    pthread_mutex_unlock(&gate); if (!valid) fail(env);
}
