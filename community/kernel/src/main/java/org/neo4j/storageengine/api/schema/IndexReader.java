/*
 * Copyright (c) 2002-2016 "Neo Technology,"
 * Network Engine for Objects in Lund AB [http://neotechnology.com]
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
package org.neo4j.storageengine.api.schema;

import java.util.List;

import org.neo4j.collection.primitive.PrimitiveLongCollections;
import org.neo4j.collection.primitive.PrimitiveLongIterator;
import org.neo4j.graphdb.Resource;
import org.neo4j.kernel.api.exceptions.index.IndexNotFoundKernelException;
import org.neo4j.register.Register.DoubleLong;

/**
 * Reader for an index. Must honor repeatable reads, which means that if a lookup is executed multiple times the
 * same result set must be returned.
 */
public interface IndexReader extends Resource
{
    /**
     * Searches this index for a certain value.
     *
     * @param value property value to search for.
     * @return ids of matching nodes.
     */
    PrimitiveLongIterator seek( Object value );

    /**
     * Searches this index for numerics values between {@code lower} and {@code upper}.
     *
     * @param lower lower numeric bound of search (inclusive).
     * @param upper upper numeric bound of search (inclusive).
     * @return ids of matching nodes.
     */
    PrimitiveLongIterator rangeSeekByNumberInclusive( Number lower, Number upper );

    /**
     * Searches this index for string values between {@code lower} and {@code upper}.
     *
     * @param lower lower numeric bound of search.
     * @param includeLower whether or not lower bound is inclusive.
     * @param upper upper numeric bound of search.
     * @param includeUpper whether or not upper bound is inclusive.
     * @return ids of matching nodes.
     */
    PrimitiveLongIterator rangeSeekByString( String lower, boolean includeLower, String upper, boolean includeUpper );

    /**
     * Searches this index for string values starting with {@code prefix}.
     *
     * @param prefix prefix that matching strings must start with.
     * @return ids of matching nodes.
     */
    PrimitiveLongIterator rangeSeekByPrefix( String prefix );

    /**
     * Scans this index returning all nodes.
     *
     * @return node ids in index.
     */
    PrimitiveLongIterator scan();

    /**
     * @param nodeId node if to match.
     * @param propertyValue property value to match.
     * @return number of index entries for the given {@code nodeId} and {@code propertyValue}.
     */
    int countIndexedNodes( long nodeId, Object propertyValue );

    /**
     * Sample this index (on the current thread)
     * @param result contains the unique values and the sampled size
     * @return the index size
     * @throws IndexNotFoundKernelException if the index is dropped while sampling
     */
    long sampleIndex( DoubleLong.Out result ) throws IndexNotFoundKernelException;

    //TODO:
    void verifyDeferredConstraints( Object accessor, int propertyKeyId ) throws Exception;

    void verifyDeferredConstraints( Object accessor, int propertyKeyId, List<Object> updatedPropertyValues )
            throws Exception;

    IndexReader EMPTY = new IndexReader()
    {
        @Override
        public PrimitiveLongIterator seek( Object value )
        {
            return PrimitiveLongCollections.emptyIterator();
        }

        @Override
        public PrimitiveLongIterator rangeSeekByNumberInclusive( Number lower, Number upper )
        {
            return PrimitiveLongCollections.emptyIterator();
        }

        @Override
        public PrimitiveLongIterator rangeSeekByString( String lower, boolean includeLower,
                                                        String upper, boolean includeUpper )
        {
            return PrimitiveLongCollections.emptyIterator();
        }

        @Override
        public PrimitiveLongIterator rangeSeekByPrefix( String prefix )
        {
            return PrimitiveLongCollections.emptyIterator();
        }

        @Override
        public PrimitiveLongIterator scan()
        {
            return PrimitiveLongCollections.emptyIterator();
        }

        // Used for checking index correctness
        @Override
        public int countIndexedNodes( long nodeId, Object propertyValue )
        {
            return 0;
        }

        @Override
        public long sampleIndex( DoubleLong.Out result )
        {
            result.write( 0l, 0l );
            return 0;
        }

        @Override
        public void verifyDeferredConstraints( Object accessor, int propertyKeyId )
                throws Exception
        {
        }

        @Override
        public void verifyDeferredConstraints( Object accessor, int propertyKeyId,
                List<Object> updatedPropertyValues ) throws Exception
        {
        }

        @Override
        public void close()
        {
        }
    };
}
