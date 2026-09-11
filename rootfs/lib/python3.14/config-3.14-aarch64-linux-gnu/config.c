/* Generated automatically from ../Modules/config.c.in by makesetup. */
/* Module configuration */

/* !!! !!! !!! This file is edited by the makesetup script !!! !!! !!! */

/* This file contains the table of built-in modules.
   See create_builtin() in import.c. */

#include "Python.h"

#ifdef __cplusplus
extern "C" {
#endif


extern PyObject* PyInit__bisect(void);
extern PyObject* PyInit__csv(void);
extern PyObject* PyInit__datetime(void);
extern PyObject* PyInit__heapq(void);
extern PyObject* PyInit__json(void);
extern PyObject* PyInit__opcode(void);
extern PyObject* PyInit__pickle(void);
extern PyObject* PyInit__random(void);
extern PyObject* PyInit__socket(void);
extern PyObject* PyInit__statistics(void);
extern PyObject* PyInit__struct(void);
extern PyObject* PyInit_array(void);
extern PyObject* PyInit_binascii(void);
extern PyObject* PyInit_cmath(void);
extern PyObject* PyInit_math(void);
extern PyObject* PyInit_select(void);
extern PyObject* PyInit__elementtree(void);
extern PyObject* PyInit_pyexpat(void);
extern PyObject* PyInit__blake2(void);
extern PyObject* PyInit__md5(void);
extern PyObject* PyInit__sha1(void);
extern PyObject* PyInit__sha2(void);
extern PyObject* PyInit__sha3(void);
extern PyObject* PyInit_unicodedata(void);
extern PyObject* PyInit__posixsubprocess(void);
extern PyObject* PyInit_fcntl(void);
extern PyObject* PyInit_grp(void);
extern PyObject* PyInit_syslog(void);
extern PyObject* PyInit_zlib(void);
extern PyObject* PyInit_atexit(void);
extern PyObject* PyInit_faulthandler(void);
extern PyObject* PyInit_posix(void);
extern PyObject* PyInit__signal(void);
extern PyObject* PyInit__tracemalloc(void);
extern PyObject* PyInit__suggestions(void);
extern PyObject* PyInit__codecs(void);
extern PyObject* PyInit__collections(void);
extern PyObject* PyInit_errno(void);
extern PyObject* PyInit__io(void);
extern PyObject* PyInit_itertools(void);
extern PyObject* PyInit__sre(void);
extern PyObject* PyInit__sysconfig(void);
extern PyObject* PyInit__thread(void);
extern PyObject* PyInit_time(void);
extern PyObject* PyInit__types(void);
extern PyObject* PyInit__typing(void);
extern PyObject* PyInit__weakref(void);
extern PyObject* PyInit__abc(void);
extern PyObject* PyInit__functools(void);
extern PyObject* PyInit__locale(void);
extern PyObject* PyInit__operator(void);
extern PyObject* PyInit__stat(void);
extern PyObject* PyInit__symtable(void);
extern PyObject* PyInit_pwd(void);

/* -- ADDMODULE MARKER 1 -- */

extern PyObject* PyMarshal_Init(void);
extern PyObject* PyInit__imp(void);
extern PyObject* PyInit_gc(void);
extern PyObject* PyInit__ast(void);
extern PyObject* PyInit__tokenize(void);
extern PyObject* PyInit__contextvars(void);
extern PyObject* _PyWarnings_Init(void);
extern PyObject* PyInit__string(void);

struct _inittab _PyImport_Inittab[] = {

    {"_bisect", PyInit__bisect},
    {"_csv", PyInit__csv},
    {"_datetime", PyInit__datetime},
    {"_heapq", PyInit__heapq},
    {"_json", PyInit__json},
    {"_opcode", PyInit__opcode},
    {"_pickle", PyInit__pickle},
    {"_random", PyInit__random},
    {"_socket", PyInit__socket},
    {"_statistics", PyInit__statistics},
    {"_struct", PyInit__struct},
    {"array", PyInit_array},
    {"binascii", PyInit_binascii},
    {"cmath", PyInit_cmath},
    {"math", PyInit_math},
    {"select", PyInit_select},
    {"_elementtree", PyInit__elementtree},
    {"pyexpat", PyInit_pyexpat},
    {"_blake2", PyInit__blake2},
    {"_md5", PyInit__md5},
    {"_sha1", PyInit__sha1},
    {"_sha2", PyInit__sha2},
    {"_sha3", PyInit__sha3},
    {"unicodedata", PyInit_unicodedata},
    {"_posixsubprocess", PyInit__posixsubprocess},
    {"fcntl", PyInit_fcntl},
    {"grp", PyInit_grp},
    {"syslog", PyInit_syslog},
    {"zlib", PyInit_zlib},
    {"atexit", PyInit_atexit},
    {"faulthandler", PyInit_faulthandler},
    {"posix", PyInit_posix},
    {"_signal", PyInit__signal},
    {"_tracemalloc", PyInit__tracemalloc},
    {"_suggestions", PyInit__suggestions},
    {"_codecs", PyInit__codecs},
    {"_collections", PyInit__collections},
    {"errno", PyInit_errno},
    {"_io", PyInit__io},
    {"itertools", PyInit_itertools},
    {"_sre", PyInit__sre},
    {"_sysconfig", PyInit__sysconfig},
    {"_thread", PyInit__thread},
    {"time", PyInit_time},
    {"_types", PyInit__types},
    {"_typing", PyInit__typing},
    {"_weakref", PyInit__weakref},
    {"_abc", PyInit__abc},
    {"_functools", PyInit__functools},
    {"_locale", PyInit__locale},
    {"_operator", PyInit__operator},
    {"_stat", PyInit__stat},
    {"_symtable", PyInit__symtable},
    {"pwd", PyInit_pwd},

/* -- ADDMODULE MARKER 2 -- */

    /* This module lives in marshal.c */
    {"marshal", PyMarshal_Init},

    /* This lives in import.c */
    {"_imp", PyInit__imp},

    /* This lives in Python/Python-ast.c */
    {"_ast", PyInit__ast},

    /* This lives in Python/Python-tokenize.c */
    {"_tokenize", PyInit__tokenize},

    /* These entries are here for sys.builtin_module_names */
    {"builtins", NULL},
    {"sys", NULL},

    /* This lives in gcmodule.c */
    {"gc", PyInit_gc},

    /* This lives in Python/_contextvars.c */
    {"_contextvars", PyInit__contextvars},

    /* This lives in _warnings.c */
    {"_warnings", _PyWarnings_Init},

    /* This lives in Objects/unicodeobject.c */
    {"_string", PyInit__string},

    /* Sentinel */
    {0, 0}
};


#ifdef __cplusplus
}
#endif
