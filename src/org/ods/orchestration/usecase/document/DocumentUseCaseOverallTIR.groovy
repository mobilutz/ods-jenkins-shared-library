package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseOverallTIR extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return OVERALL_LITERAL + LeVADocumentUseCase.DocumentType.TIR.name()
    }
}
