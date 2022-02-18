package org.ods.orchestration.usecase

import org.ods.orchestration.mapper.ComponentDataLeVADocumentParamsMapper
import org.ods.orchestration.mapper.DefaultLeVADocumentParamsMapper
import org.ods.orchestration.mapper.TestDataLeVADocumentParamsMapper
import org.ods.orchestration.util.Project
import org.ods.util.IPipelineSteps

final class LeVADocumentParamsFactory {

    private LeVADocumentParamsFactory() {
        //to avoid instantiation
    }

    static Map getInstance(Project project,
                           IPipelineSteps steps,
                           Map testData,
                           Map repo) {

        if (project == null) {
            throw new RuntimeException("project is null")
        }

        if (steps == null) {
            throw new RuntimeException("steps is null")
        }

        if (testData != null && repo != null) {
            return new ComponentDataLeVADocumentParamsMapper(project, steps, testData, repo).build()
        }

        if (testData != null && repo == null) {
            new TestDataLeVADocumentParamsMapper(project, steps, testData).build()
        }

        return new DefaultLeVADocumentParamsMapper(project, steps).build()
    }
}
