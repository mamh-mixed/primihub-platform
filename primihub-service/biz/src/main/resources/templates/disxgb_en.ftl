#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
 Copyright 2022 Primihub

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

      https://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 """
import primihub as ph
from primihub import dataset, context
from primihub.primitive.opt_paillier_c2py_warpper import *
from primihub.channel.zmq_channel import IOService, Session
from primihub.FL.model.xgboost.xgb_guest_en import XGB_GUEST_EN
from primihub.FL.model.xgboost.xgb_host_en import XGB_HOST_EN
from primihub.FL.model.xgboost.xgb_guest import XGB_GUEST
from primihub.FL.model.xgboost.xgb_host import XGB_HOST
from primihub.FL.model.evaluation.evaluation import Regression_eva
from primihub.FL.model.evaluation.evaluation import Classification_eva
import pandas as pd
import numpy as np
import logging
import pickle


def get_logger(name):
    LOG_FORMAT = "[%(asctime)s][%(filename)s:%(lineno)d][%(levelname)s] %(message)s"
    DATE_FORMAT = "%m/%d/%Y %H:%M:%S %p"
    logging.basicConfig(level=logging.DEBUG,
                        format=LOG_FORMAT, datefmt=DATE_FORMAT)
    logger = logging.getLogger(name)
    return logger


logger = get_logger("xgb_host")

dataset.define("${guest_dataset}")
dataset.define("${label_dataset}")

ph.context.Context.func_params_map = {
    "xgb_host_logic": ("paillier",),
    "xgb_guest_logic": ("paillier",)
}

# Number of tree to fit.
num_tree = 1
# Max depth of each tree.
max_depth = 1


@ph.context.function(role='host', protocol='xgboost', datasets=["${label_dataset}"], next_peer="*:12120")
def xgb_host_logic(cry_pri="paillier"):
    print("start xgb host logic...")
    next_peer = ph.context.Context.nodes_context["host"].next_peer
    print(ph.context.Context.datasets)
    print(ph.context.Context.dataset_map)
    ip, port = next_peer.split(":")
    ios = IOService()
    server = Session(ios, ip, port, "server")

    channel = server.addChannel()
    data = ph.dataset.read(dataset_key="${label_dataset}").df_data
    dim = data.shape[0]
    dim_train = dim / 10 * 8
    data_train = data.loc[:dim_train, :].reset_index(drop=True)
    data_test = data.loc[dim_train:dim, :].reset_index(drop=True)
    label_true = ['${label_field}']
    y_true = data_test['${label_field}'].values
    data_test = data_test[
        [x for x in data_test.columns if x not in label_true]
    ]
    logger.info(data_test.head())

    labels = ['${label_field}']  # noqa
    X_host = data_train[
        [x for x in data.columns if x not in labels]
    ]
    Y = data_train['${label_field}'].values

    if cry_pri == "paillier":
        xgb_host = XGB_HOST_EN(n_estimators=num_tree, max_depth=max_depth, reg_lambda=1,
                               sid=0, min_child_weight=1, objective='linear', channel=channel)
        channel.recv()
        xgb_host.channel.send(xgb_host.pub)
        print(xgb_host.channel.recv())
        y_hat = np.array([0.5] * Y.shape[0])

        for t in range(xgb_host.n_estimators):
            logger.info("Begin to trian tree {}.".format(t + 1))

            xgb_host.record = 0
            xgb_host.lookup_table = pd.DataFrame(
                columns=['record_id', 'feature_id', 'threshold_value'])
            f_t = pd.Series([0] * Y.shape[0])
            gh = xgb_host.get_gh(y_hat, Y)
            gh_en = pd.DataFrame(columns=['g', 'h'])
            for item in gh.columns:
                for index in gh.index:
                    gh_en.loc[index, item] = opt_paillier_encrypt_crt(xgb_host.pub, xgb_host.prv,
                                                                      int(gh.loc[index, item]))
            logger.info("Encrypt finish.")

            xgb_host.channel.send(gh_en)
            GH_guest_en = xgb_host.channel.recv()
            GH_guest = pd.DataFrame(
                columns=['G_left', 'G_right', 'H_left', 'H_right', 'var', 'cut'])
            for item in [x for x in GH_guest_en.columns if x not in ['cut', 'var']]:
                for index in GH_guest_en.index:
                    if GH_guest_en.loc[index, item] == 0:
                        GH_guest.loc[index, item] = 0
                    else:
                        GH_guest.loc[index, item] = opt_paillier_decrypt_crt(xgb_host.pub, xgb_host.prv,
                                                                             GH_guest_en.loc[index, item])

            logger.info("Decrypt finish.")

            for item in [x for x in GH_guest_en.columns if x not in ['G_left', 'G_right', 'H_left', 'H_right']]:
                for index in GH_guest_en.index:
                    GH_guest.loc[index, item] = GH_guest_en.loc[index, item]

            xgb_host.tree_structure[t + 1], f_t = xgb_host.xgb_tree(X_host, GH_guest, gh, f_t, 0)  # noqa
            xgb_host.lookup_table_sum[t + 1] = xgb_host.lookup_table
            y_hat = y_hat + xgb_host.learning_rate * f_t

            logger.info("Finish to trian tree {}.".format(t + 1))

        predict_file_path = ph.context.Context.get_predict_file_path()
        indicator_file_path = ph.context.Context.get_indicator_file_path()
        model_file_path = ph.context.Context.get_model_file_path()
        lookup_file_path = ph.context.Context.get_host_lookup_file_path()
        with open(model_file_path, 'wb') as fm:
            pickle.dump(xgb_host.tree_structure, fm)
        with open(lookup_file_path, 'wb') as fl:
            pickle.dump(xgb_host.lookup_table_sum, fl)
        y_pre = xgb_host.predict_prob(data_test)
        Classification_eva.get_result(y_true, y_pre, indicator_file_path)
        xgb_host.predict_prob(X_host).to_csv(predict_file_path)

    elif cry_pri == "plaintext":
        xgb_host = XGB_HOST(n_estimators=num_tree, max_depth=max_depth, reg_lambda=1,
                            sid=0, min_child_weight=1, objective='linear', channel=channel)
        channel.recv()
        y_hat = np.array([0.5] * Y.shape[0])
        for t in range(xgb_host.n_estimators):
            logger.info("Begin to trian tree {}.".format(t))

            xgb_host.record = 0
            xgb_host.lookup_table = pd.DataFrame(
                columns=['record_id', 'feature_id', 'threshold_value'])
            f_t = pd.Series([0] * Y.shape[0])
            gh = xgb_host.get_gh(y_hat, Y)
            xgb_host.channel.send(gh)
            GH_guest = xgb_host.channel.recv()
            xgb_host.tree_structure[t + 1], f_t = xgb_host.xgb_tree(X_host, GH_guest, gh, f_t, 0)  # noqa
            xgb_host.lookup_table_sum[t + 1] = xgb_host.lookup_table
            y_hat = y_hat + xgb_host.learning_rate * f_t

            logger.info("Finish to trian tree {}.".format(t))

        predict_file_path = ph.context.Context.get_predict_file_path()
        indicator_file_path = ph.context.Context.get_indicator_file_path()
        model_file_path = ph.context.Context.get_model_file_path()
        lookup_file_path = ph.context.Context.get_host_lookup_file_path()
        with open(model_file_path, 'wb') as fm:
            pickle.dump(xgb_host.tree_structure, fm)
        with open(lookup_file_path, 'wb') as fl:
            pickle.dump(xgb_host.lookup_table_sum, fl)
        y_pre = xgb_host.predict_prob(data_test)
        Classification_eva.get_result(y_true, y_pre, indicator_file_path)
        xgb_host.predict_prob(data_test).to_csv(predict_file_path)


@ph.context.function(role='guest', protocol='xgboost', datasets=["${guest_dataset}"], next_peer="localhost:12120")
def xgb_guest_logic(cry_pri="paillier"):
    print("start xgb guest logic...")
    ios = IOService()
    next_peer = ph.context.Context.nodes_context["guest"].next_peer
    print(ph.context.Context.nodes_context["guest"])
    print(ph.context.Context.datasets)
    print(ph.context.Context.dataset_map)
    print("guest next peer: ", next_peer)
    ip, port = next_peer.split(":")
    client = Session(ios, ip, port, "client")
    channel = client.addChannel()

    data = ph.dataset.read(dataset_key="${guest_dataset}").df_data
    dim = data.shape[0]
    dim_train = dim / 10 * 8
    X_guest = data.loc[:dim_train, :].reset_index(drop=True)
    data_test = data.loc[dim_train:dim, :].reset_index(drop=True)

    if cry_pri == "paillier":
        xgb_guest = XGB_GUEST_EN(n_estimators=num_tree, max_depth=max_depth, reg_lambda=1, min_child_weight=1,
                                 objective='linear',
                                 sid=1, channel=channel)  # noqa
        channel.send(b'guest ready')
        pub = xgb_guest.channel.recv()
        xgb_guest.channel.send(b'recved pub')

        for t in range(xgb_guest.n_estimators):
            xgb_guest.record = 0
            xgb_guest.lookup_table = pd.DataFrame(
                columns=['record_id', 'feature_id', 'threshold_value'])
            gh_host = xgb_guest.channel.recv()
            X_guest_gh = pd.concat([X_guest, gh_host], axis=1)
            print(X_guest_gh)
            gh_sum = xgb_guest.get_GH(X_guest_gh, pub)
            xgb_guest.channel.send(gh_sum)
            xgb_guest.cart_tree(X_guest_gh, 0, pub)
            xgb_guest.lookup_table_sum[t + 1] = xgb_guest.lookup_table
        lookup_file_path = ph.context.Context.get_guest_lookup_file_path()
        with open(lookup_file_path, 'wb') as fl:
            pickle.dump(xgb_guest.lookup_table_sum, fl)
        xgb_guest.predict(data_test)
        xgb_guest.predict(X_guest)
    elif cry_pri == "plaintext":
        xgb_guest = XGB_GUEST(n_estimators=num_tree, max_depth=max_depth, reg_lambda=1, min_child_weight=1,
                              objective='linear',
                              sid=1, channel=channel)  # noqa
        channel.send(b'guest ready')
        for t in range(xgb_guest.n_estimators):
            xgb_guest.record = 0
            xgb_guest.lookup_table = pd.DataFrame(
                columns=['record_id', 'feature_id', 'threshold_value'])
            gh_host = xgb_guest.channel.recv()
            X_guest_gh = pd.concat([X_guest, gh_host], axis=1)
            print(X_guest_gh)
            gh_sum = xgb_guest.get_GH(X_guest_gh)
            xgb_guest.channel.send(gh_sum)
            xgb_guest.cart_tree(X_guest_gh, 0)
            xgb_guest.lookup_table_sum[t + 1] = xgb_guest.lookup_table
        lookup_file_path = ph.context.Context.get_guest_lookup_file_path()
        with open(lookup_file_path, 'wb') as fl:
            pickle.dump(xgb_guest.lookup_table_sum, fl)
        xgb_guest.predict(data_test)
        xgb_guest.predict(X_guest)