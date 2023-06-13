/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.kernel.api.impl.schema.vector;

import static org.apache.lucene.document.Field.Store.YES;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.neo4j.values.storable.FloatArray;
import org.neo4j.values.storable.FloatingPointArray;

class VectorDocumentStructure {
    static final String ENTITY_ID_KEY = "id";
    static final String VECTOR_VALUE_KEY = "vector";

    static Term newTermForChangeOrRemove(long id) {
        return new Term(ENTITY_ID_KEY, Long.toString(id));
    }

    static Document createLuceneDocument(
            long id, FloatingPointArray value, VectorSimilarityFunction similarityFunction) {
        final float[] vector = vectorFrom(value);
        final var document = new Document();
        final var idField = new StringField(ENTITY_ID_KEY, Long.toString(id), YES);
        final var idValueField = new NumericDocValuesField(ENTITY_ID_KEY, id);
        document.add(idField);
        document.add(idValueField);
        final var valueField = new KnnFloatVectorField(VECTOR_VALUE_KEY, vector, similarityFunction);
        document.add(valueField);
        return document;
    }

    private static float[] vectorFrom(FloatingPointArray value) {
        if (value instanceof final FloatArray floatArray) {
            return floatArray.asObjectCopy();
        }

        final float[] vector = new float[value.length()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) value.doubleValue(i);
        }
        return vector;
    }
}
