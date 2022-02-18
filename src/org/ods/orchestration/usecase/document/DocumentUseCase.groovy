package org.ods.orchestration.usecase.document

import org.ods.orchestration.service.DocGenService
import org.ods.orchestration.usecase.LeVADocumentParamsFactory
import org.ods.orchestration.util.Project
import org.ods.util.ILogger
import org.ods.util.IPipelineSteps

abstract class DocumentUseCase {

    public static final String OVERALL_LITERAL = "Overall";

    boolean apply(String documentName) {
        return getDocumentName() == documentName;
    }

    abstract String getDocumentName();

    String create(ILogger logger, Project project, IPipelineSteps steps, String projectId, String buildNumber, DocGenService docGen, Map testData = null, Map repo = null) {
        logger.info("create document ${getDocumentName()} start")
        Map data = LeVADocumentParamsFactory.getInstance(project, steps, testData, repo)
        Map document = docGen.createDocument(projectId, buildNumber, getDocumentName(), data)
        logger.info("create document ${getDocumentName()} return:${document.nexusURL}")
        document.nexusURL
    }
}
