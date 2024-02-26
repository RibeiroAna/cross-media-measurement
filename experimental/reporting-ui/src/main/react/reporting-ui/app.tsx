/* Copyright 2023 The Cross-Media Measurement Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License. */

import React from 'react';
import { routes } from './route';
import { createBrowserRouter, RouterProvider } from 'react-router-dom';
import FeedbackButton from './component/feedback_button/feedback_button';
import './app.css';

const router = createBrowserRouter(routes);

const App = () => {
    return (
        <React.Fragment>
            <div className="content">
                <RouterProvider router={router} />
            </div>
            <FeedbackButton />
        </React.Fragment >
    );
}

export default App;