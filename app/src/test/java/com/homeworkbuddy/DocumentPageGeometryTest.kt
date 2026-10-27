package com.homeworkbuddy

import org.junit.Assert.*
import org.junit.Test

class DocumentPageGeometryTest {
    @Test fun fullPagePreservesImageDimensions() {
        assertTrue(DocumentPageGeometry.isValid(DocumentPageGeometry.fullPage))
        assertEquals(2400 to 3200, DocumentPageGeometry.outputSize(DocumentPageGeometry.fullPage, 2400, 3200))
    }

    @Test fun crossedOrCollapsedPageCannotBeSelected() {
        assertFalse(DocumentPageGeometry.isValid(listOf(PagePoint(0f, 0f), PagePoint(1f, 1f), PagePoint(1f, 0f), PagePoint(0f, 1f))))
        assertFalse(DocumentPageGeometry.isValid(List(4) { PagePoint(0.5f, 0.5f) }))
        assertFalse(DocumentPageGeometry.isValid(DocumentPageGeometry.fullPage.reversed()))
        assertFalse(DocumentPageGeometry.isValid(DocumentPageGeometry.fullPage.toMutableList().apply { this[0] = PagePoint(Float.NaN, 0f) }))
    }

    @Test fun cropUsesPageEdgesRatherThanIncludingOutsideBackground() {
        val points = listOf(PagePoint(.2f, .1f), PagePoint(.8f, .1f), PagePoint(.8f, .9f), PagePoint(.2f, .9f))
        assertTrue(DocumentPageGeometry.isValid(points))
        assertEquals(600 to 1600, DocumentPageGeometry.outputSize(points, 1000, 2000))
    }

    @Test fun perspectivePageRetainsLongerEdgeResolution() {
        val points = listOf(PagePoint(.3f, .1f), PagePoint(.7f, .1f), PagePoint(.9f, .9f), PagePoint(.1f, .9f))
        assertTrue(DocumentPageGeometry.isValid(points))
        assertEquals(800, DocumentPageGeometry.outputSize(points, 1000, 2000).first)
    }
}
