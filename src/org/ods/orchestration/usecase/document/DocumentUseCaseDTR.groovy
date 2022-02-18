package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseDTR extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return LeVADocumentUseCase.DocumentType.DTR.name()
    }
}
