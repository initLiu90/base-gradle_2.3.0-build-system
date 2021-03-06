/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.builder.symbols;

import com.android.annotations.NonNull;
import com.android.utils.FileUtils;
import com.android.utils.Pair;
import com.google.common.base.Preconditions;
import java.io.File;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Utility class to generate {@code R.java} files.
 */
public class RGeneration {

    private RGeneration() {}

    /**
     * Given a symbol table for the main program (that could be an application, a library or
     * anything that actually generates symbols), and given the symbol tables for all libraries it
     * depends on, generates the {@code R.java} files for each individual library.
     *
     * <p>The {@code R.java} file for the main symbol table is assumed to be generated already.
     *
     * @param main the main symbol file
     * @param libraries the libraries to generate symbols to
     * @param out the output directory where files are generated, must exist and be a directory
     * @param finalIds should final IDs be generated? This should be {@code false} if and only if
     * the artifact being generated is a library or other reusable module and not a final apk
     */
    public static void generateRForLibraries(
            @NonNull SymbolTable main,
            @NonNull Collection<SymbolTable> libraries,
            @NonNull File out,
            boolean finalIds) {
        Preconditions.checkArgument(out.isDirectory(), "!out.iDirectory");

        /*
         * First we need to make a few changes to the actual symbol tables we are going to write.
         *
         * We don't write symbol tables for libraries that have the same package and table name as
         * the main symbol table because that file is already generated.
         *
         * Then, we must merge symbol tables if they have the same package and name as symbols for
         * both are read from the same base files.
         */
        Pair<String, String> mainP = Pair.of(main.getTablePackage(), main.getTableName());
        Map<Pair<String, String>, SymbolTable> toWrite = new HashMap<>();
        for (SymbolTable st : libraries) {
            Pair<String, String> p = Pair.of(st.getTablePackage(), st.getTableName());
            if (p.equals(mainP)) {
                continue;
            }

            SymbolTable existing = toWrite.get(p);
            if (existing != null) {
                toWrite.put(p, existing.merge(st));
            } else {
                toWrite.put(p, st);
            }
        }

        /*
         * Replace the values of the symbols in the tables to write with the ones in the main
         * symbol table.
         */
        for (Pair<String, String> k : new HashSet<>(toWrite.keySet())) {
            SymbolTable st = toWrite.get(k);
            st = main.filter(st).rename(st.getTablePackage(), st.getTableName());
            toWrite.put(k, st);

            /*
             * Symbols may actually disappear from the library's symbol table. This can happen
             * with library resolution. For example:
             *
             * - Library A version 1 has resource X; it's symbol table will include X and resource
             * X will exist in the library.
             * - Library B version 1 depends on library A version 1; it's symbol table will include
             * X ("inherited" from A), but it won't include resource X since the resource is in
             * library A version 1.
             * - Library A version 2 does not have resource X; it does not exist in A's symbol
             * list nor in its resources.
             * - Library (or application) depends on Library B version 1 *and* Library A version 2.
             *
             * During dependency resolution, when building C we end up with Library A version 2 and
             * Library B, but Library A version 1 is ignored (because version 2 is included). This
             * means that symbol X will exist in Library B's symbol table, but the actual resource
             * does not exist so it won't exist in the main symbol table, which is built from the
             * existing resources.
             *
             * The file we generate for A, in this case, will not include symbol X, although it was
             * in A's original symbol table.
             */
        }

        /*
         * Now write everything.
         */
        toWrite.values().forEach(st -> SymbolIo.exportToJava(st, out, finalIds));
    }
}
