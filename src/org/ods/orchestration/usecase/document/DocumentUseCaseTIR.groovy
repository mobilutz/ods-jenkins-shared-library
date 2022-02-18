package org.ods.orchestration.usecase.document


import org.ods.orchestration.usecase.LeVADocumentUseCase

class DocumentUseCaseTIR extends DocumentUseCase {

    @Override
    String getDocumentName() {
        return LeVADocumentUseCase.DocumentType.TIR.name()
    }
}
