/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2010, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.jboss.as.ejb3.component;

import org.jboss.as.ee.component.BasicComponent;
import org.jboss.as.server.deployment.DeploymentUnit;
import org.jboss.ejb3.tx2.spi.TransactionalComponent;
import org.jboss.logging.Logger;
import org.jboss.msc.service.ServiceController;

import javax.ejb.ApplicationException;
import javax.ejb.EJBHome;
import javax.ejb.EJBLocalHome;
import javax.ejb.TimerService;
import javax.ejb.TransactionAttributeType;
import javax.ejb.TransactionManagementType;
import javax.transaction.TransactionManager;
import javax.transaction.TransactionSynchronizationRegistry;
import javax.transaction.UserTransaction;
import java.lang.reflect.Method;
import java.security.Principal;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

/**
 * @author <a href="mailto:cdewolf@redhat.com">Carlo de Wolf</a>
 */
public abstract class EJBComponent extends BasicComponent implements org.jboss.ejb3.context.spi.EJBComponent, TransactionalComponent {
    private static Logger log = Logger.getLogger(EJBComponent.class);

    private final ConcurrentMap<MethodIntf, ConcurrentMap<String, ConcurrentMap<ArrayKey, TransactionAttributeType>>> txAttrs;

    private final EJBUtilities utilities;
    private final boolean isBeanManagedTransaction;
    private static volatile boolean youHaveBeenWarnedEJBTHREE2120 = false;
    private final Map<Class<?>, ApplicationException> applicationExceptions;

    /**
     * Construct a new instance.
     *
     * @param ejbComponentCreateService the component configuration
     */
    protected EJBComponent(final EJBComponentCreateService ejbComponentCreateService) {
        super(ejbComponentCreateService);


        //this.applicationExceptions = configuration.getEjbJarConfiguration().getApplicationExceptions();

        // constructs
        final DeploymentUnit deploymentUnit = ejbComponentCreateService.getDeploymentUnitInjector().getValue();
        final ServiceController<EJBUtilities> serviceController = (ServiceController<EJBUtilities>) deploymentUnit.getServiceRegistry().getRequiredService(EJBUtilities.SERVICE_NAME);
        this.utilities = serviceController.getValue();


        txAttrs = ejbComponentCreateService.getTxAttrs();
        isBeanManagedTransaction = TransactionManagementType.BEAN.equals(ejbComponentCreateService.getTransactionManagementType());
        applicationExceptions = ejbComponentCreateService.getApplicationExceptions();
    }

    @Override
    public ApplicationException getApplicationException(Class<?> exceptionClass) {
        ApplicationException applicationException = this.applicationExceptions.get(exceptionClass);
        if (applicationException != null) {
            return applicationException;
        }
        // Check if the super class of the passed exception class, is an application exception.
        Class<?> superClass = exceptionClass.getSuperclass();
        while (superClass != null && !(superClass.equals(Exception.class) || superClass.equals(Object.class))) {
            applicationException = this.applicationExceptions.get(superClass);
            // check whether the "inherited" attribute is set. A subclass of an application exception
            // is an application exception only if the inherited attribute on the parent application exception
            // is set to true.
            if (applicationException != null) {
                if (applicationException.inherited()) {
                    return applicationException;
                }
                // Once we find a super class which is an application exception,
                // we just stop there (no need to check the grand super class), irrespective of whether the "inherited"
                // is true or false
                return null; // not an application exception, so return null
            }
            // move to next super class
            superClass = superClass.getSuperclass();
        }
        // not an application exception, so return null.
        return null;
    }

    @Override
    public EJBHome getEJBHome() throws IllegalStateException {
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.getEJBHome");
    }

    @Override
    public EJBLocalHome getEJBLocalHome() throws IllegalStateException {
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.getEJBLocalHome");
    }

    @Override
    public boolean getRollbackOnly() throws IllegalStateException {
        if (isBeanManagedTransaction())
            throw new IllegalStateException("EJB 3.1 FR 4.3.3 & 5.4.5 Only beans with container-managed transaction demarcation can use this method.");
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.getRollbackOnly");
    }

    @Override
    public TimerService getTimerService() throws IllegalStateException {
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.getTimerService");
    }

    @Deprecated
    public TransactionAttributeType getTransactionAttributeType(Method method) {
        if (!youHaveBeenWarnedEJBTHREE2120) {
            log.warn("EJBTHREE-2120: deprecated getTransactionAttributeType method called (dev problem)");
            youHaveBeenWarnedEJBTHREE2120 = true;
        }
        return getTransactionAttributeType(MethodIntf.BEAN, method);
    }

    public TransactionAttributeType getTransactionAttributeType(MethodIntf methodIntf, Method method) {
        ConcurrentMap<String, ConcurrentMap<ArrayKey, TransactionAttributeType>> perMethodIntf = txAttrs.get(methodIntf);
        if (perMethodIntf == null)
            throw new IllegalStateException("Can't find tx attrs for view type " + methodIntf + " on bean named " + this.getComponentName());
        ConcurrentMap<ArrayKey, TransactionAttributeType> perMethod = perMethodIntf.get(method.getName());
        if (perMethod == null)
            throw new IllegalStateException("Can't find tx attrs for method name " + method.getName() + " on view type " + methodIntf + " on bean named " + this.getComponentName());
        TransactionAttributeType txAttr = perMethod.get(new ArrayKey((Object[]) method.getParameterTypes()));
        if (txAttr == null)
            throw new IllegalStateException("Can't find tx attr for method " + method + " on view type " + methodIntf + " on bean named " + this.getComponentName());
        return txAttr;
    }

    @Override
    public TransactionManager getTransactionManager() {
        return utilities.getTransactionManager();
    }

    public TransactionSynchronizationRegistry getTransactionSynchronizationRegistry() {
        return utilities.getTransactionSynchronizationRegistry();
    }

    @Override
    public int getTransactionTimeout(Method method) {
        return -1; // un-configured
    }

    @Override
    public UserTransaction getUserTransaction() throws IllegalStateException {
        if (!isBeanManagedTransaction())
            throw new IllegalStateException("EJB 3.1 FR 4.3.3 & 5.4.5 Only beans with bean-managed transaction demarcation can use this method.");
        return utilities.getUserTransaction();
    }

    private boolean isBeanManagedTransaction() {
        return isBeanManagedTransaction;
    }

    @Override
    public boolean isCallerInRole(Principal callerPrincipal, String roleName) throws IllegalStateException {
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.isCallerInRole");
    }

    @Override
    public Object lookup(String name) throws IllegalArgumentException {
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.lookup");
    }

    @Override
    public void setRollbackOnly() throws IllegalStateException {
        if (isBeanManagedTransaction())
            throw new IllegalStateException("EJB 3.1 FR 4.3.3 & 5.4.5 Only beans with container-managed transaction demarcation can use this method.");
        throw new RuntimeException("NYI: org.jboss.as.ejb3.component.EJBComponent.setRollbackOnly");
    }
}
