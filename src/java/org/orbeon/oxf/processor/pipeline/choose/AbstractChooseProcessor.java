/**
 *  Copyright (C) 2004 Orbeon, Inc.
 *
 *  This program is free software; you can redistribute it and/or modify it under the terms of the
 *  GNU Lesser General Public License as published by the Free Software Foundation; either version
 *  2.1 of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 *  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 *  See the GNU Lesser General Public License for more details.
 *
 *  The full text of the license is available at http://www.gnu.org/copyleft/lesser.html
 */
package org.orbeon.oxf.processor.pipeline.choose;

import org.apache.commons.collections.CollectionUtils;
import org.dom4j.Element;
import org.orbeon.oxf.common.ValidationException;
import org.orbeon.oxf.pipeline.api.PipelineContext;
import org.orbeon.oxf.processor.AbstractProcessor;
import org.orbeon.oxf.processor.Processor;
import org.orbeon.oxf.processor.ProcessorImpl;
import org.orbeon.oxf.processor.pipeline.PipelineProcessor;
import org.orbeon.oxf.processor.pipeline.ast.*;
import org.orbeon.oxf.xml.XMLConstants;
import org.orbeon.oxf.xml.XMLUtils;

import java.util.*;

/**
 * <p><b>Usage</b>
 * <p>This processor is not intended to be used by itelf. Instead, it is used by
 * the PipelineProcessor (so we can keep the PipelineProcessor simpler and
 * see the "choose" functionnality as if it was just an other processor).
 *
 * <p><b>Lifecycle</b>
 * <ol>
 *     <li>createInput("config") is called.
 *     <li>createInstance() is called.
 *     <li>At this point the config (<p:choose> block) is read and we determine
 *         for each branch all the inputs (<input ref="..."> with no corresponding
 *         <output id="...">) and all the outputs (<output id="..."> with no
 *         <input ref="...">).
 *     <li>We ensure that the collection of outputs is exactly the
 *         same for each branch.
 *     <li>We create a pipeline config with the text of each branch, adding the
 *         appropriate <p:param> and appropriate ref="..." attribute to reference
 *         those parameters.
 *     <li>The collections of pipelines can be cached.
 *     <li>We read the input referenced in the <p:choose> and choose which pipeline
 *         has to be executed.
 *     <li>We connect the selected pipeline with the input of this processor.
 *     <li>We start every all the outputs of the pipeline, store the result in a
 *         SAXStore and play it back when the read of the corresponding output
 *         is called.
 * </ol>
 */
public class AbstractChooseProcessor extends ProcessorImpl implements AbstractProcessor {

    public static final String CHOOSE_DATA_INPUT = "$data";
    private ASTChoose chooseAST;
    private Object validity;

    public AbstractChooseProcessor(ASTChoose chooseAST, Object validity) {
        this.chooseAST = chooseAST;
        this.validity = validity;
        setLocationData(chooseAST.getLocationData());
    }

    public Processor createInstance(PipelineContext context) {

        // We store here the "refs with no id" and "ids with no ref" for each branch.
        // Those are list of collections (one collection for each branch).
        List refsWithNoId = new ArrayList();
        List idsWithNoRef = new ArrayList();
        List paramRefs = new ArrayList();

        for (Iterator astIterator = chooseAST.getWhen().iterator(); astIterator.hasNext();) {

            // Get info about id used in this branch
            final ASTWhen when = (ASTWhen) astIterator.next();
            IdInfo idInfo = when.getIdInfo();
            paramRefs.add(idInfo.getOutputRefs());

            // Determine all <p:input ref="..."> with no <p:output id="...">.
            // Those are the inputs of this processor.
            Set branchRefsWithNoId = new HashSet(idInfo.getInputRefs());
            branchRefsWithNoId.removeAll(idInfo.getOutputIds());
            refsWithNoId.add(branchRefsWithNoId);

            // Determine all <p:output id="..."> with no <p:input ref="...">.
            // Those are the outputs of this processor.
            Set branchIdsWithNoRef = new HashSet(idInfo.getOutputIds());
            branchIdsWithNoRef.removeAll(idInfo.getInputRefs());
            idsWithNoRef.add(branchIdsWithNoRef);
        }

        // Make sure that the "ids with no ref" are the same for each branch
        if (idsWithNoRef.size() > 1) {
            Collection firstBranchIdsWithNoRef = (Collection) idsWithNoRef.get(0);
            int branchId = 0;
            for (Iterator i = idsWithNoRef.iterator(); i.hasNext();) {
                branchId++;
                Collection branchIdsWithNoRef = (Collection) i.next();
                if (branchIdsWithNoRef != firstBranchIdsWithNoRef &&
                        !CollectionUtils.isEqualCollection(branchIdsWithNoRef, firstBranchIdsWithNoRef))
                    throw new ValidationException("ASTChoose branch number " + branchId +
                            " does not declare the same ids " + branchIdsWithNoRef.toString() +
                            " as the previous branches " + firstBranchIdsWithNoRef.toString(), getLocationData());
            }
        }

        // Make sure that the "param ref" are the same for each branch
        if (paramRefs.size() > 1) {
            Collection firstBranchParamRefs = (Collection) paramRefs.get(0);
            int branchId = 0;
            for (Iterator i = paramRefs.iterator(); i.hasNext();) {
                branchId++;
                Collection branchParamRefs = (Collection) i.next();
                if (branchParamRefs != firstBranchParamRefs &&
                        !CollectionUtils.isEqualCollection(branchParamRefs, firstBranchParamRefs))
                    throw new ValidationException("ASTChoose branch number " + branchId +
                            " does not declare the same refs " + branchParamRefs.toString() +
                            " as the previous branches " + firstBranchParamRefs.toString(), getLocationData());
            }
        }

        // Compute the union of "refs with no id" for all the branches
        final Set allRefsWithNoId = new HashSet();
        for (Iterator i = refsWithNoId.iterator(); i.hasNext();)
            allRefsWithNoId.addAll((Set) i.next());

        // Create the list of inputs based on allRefsWithNoId
        List astParams = new ArrayList();
        for (int i = 0; i < 2; i++) {
            Set parameters;
            if (i == 0) {
                parameters = allRefsWithNoId;
            } else {
                parameters = new HashSet();
                parameters.addAll((Set) idsWithNoRef.get(0));
                parameters.addAll((Set) paramRefs.get(0));
            }

            for (Iterator j = parameters.iterator(); j.hasNext();) {
                String paramName = (String) j.next();
                ASTParam astParam = new ASTParam();
                astParam.setType(i == 0 ? ASTParam.INPUT : ASTParam.OUTPUT);
                astParam.setName(paramName);
                astParams.add(astParam);
            }
        }

        // For each branch, create a new pipeline processor
        List branchProcessors = new ArrayList();
        List branchConditions = new ArrayList();
        List branchNamespaces = new ArrayList();

        for (Iterator astIterator = chooseAST.getWhen().iterator(); astIterator.hasNext();) {
            ASTWhen astWhen = (ASTWhen) astIterator.next();

            // Save condition
            branchConditions.add(astWhen.getTest());
            // Get namespaces declared at this point in the pipeline
            if (astWhen.getNode() != null && astWhen.getNamespaces().size() != 0) {
                throw new ValidationException("ASTWhen cannot have both a node and namespaces defined", astWhen.getLocationData());
            }
            // jaxen stuff
//            branchNamespaces.add(new SimpleNamespaceContext(astWhen.getNode() != null
//                    ? XMLUtils.getNamespaceContext((Element) astWhen.getNode())
//                    : astWhen.getNamespaces()));
            branchNamespaces.add(astWhen.getNode() != null
                    ? XMLUtils.getNamespaceContext((Element) astWhen.getNode())
                    : astWhen.getNamespaces());

            // Add an identity processor to connect the output of the branch to
            // the <param type="output"> of the pipeline
            final Set idsToConvert = (Set) idsWithNoRef.get(0);
            for (Iterator i = idsToConvert.iterator(); i.hasNext();) {
                String id = (String) i.next();
                ASTProcessorCall identityConnector = new ASTProcessorCall(XMLConstants.IDENTITY_PROCESSOR_QNAME); {
                    identityConnector.addInput(new ASTInput("data", new ASTHrefId(new ASTOutput(null, id))));
                    identityConnector.addOutput(new ASTOutput("data", new ASTParam(ASTParam.OUTPUT, id)));
                }
                astWhen.addStatement(identityConnector);
            }

            ASTPipeline astPipeline = new ASTPipeline();
            astPipeline.setValidity(validity);
            astPipeline.getParams().addAll(astParams);
            astPipeline.getStatements().addAll(astWhen.getStatements());
            astPipeline.setNode(astWhen.getNode());
            Processor pipelineProcessor = new PipelineProcessor(astPipeline);
            if (getId() != null)
                pipelineProcessor.setId(getId() + "-branch" + branchProcessors.size());
            branchProcessors.add(pipelineProcessor);
        }

        return new ConcreteChooseProcessor(getId(), getLocationData(),
                branchConditions, branchNamespaces, branchProcessors,
                allRefsWithNoId, (Set) idsWithNoRef.get(0), (Set) paramRefs.get(0));
    }
}
