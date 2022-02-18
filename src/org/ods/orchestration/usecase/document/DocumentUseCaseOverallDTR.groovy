package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseOverallDTR extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return OVERALL_LITERAL + LeVADocumentUseCase.DocumentType.DTR.name()
    }
}
