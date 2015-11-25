/*
 *  Copyright 2015 esbtools Contributors and/or its affiliates.
 *
 *  This file is part of esbtools.
 *
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.esbtools.eventhandler;

import java.util.Objects;

public final class MergedDocumentEvent {
    private final DocumentEvent original;
    private final DocumentEvent mergedInto;

    public MergedDocumentEvent(DocumentEvent original, DocumentEvent mergedInto) {
        this.original = original;
        this.mergedInto = mergedInto;
    }

    public DocumentEvent original() {
        return original;
    }

    public DocumentEvent mergedInto() {
        return mergedInto;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MergedDocumentEvent that = (MergedDocumentEvent) o;
        return Objects.equals(original, that.original) &&
                Objects.equals(mergedInto, that.mergedInto);
    }

    @Override
    public int hashCode() {
        return Objects.hash(original, mergedInto);
    }

    @Override
    public String toString() {
        return "MergedDocumentEvents{" +
                "original=" + original +
                ", mergedInto=" + mergedInto +
                '}';
    }
}
